package com.campus.tracker.application.importing;

import com.campus.tracker.auth.*;
import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.*;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;
import static com.campus.tracker.application.importing.ImportDtos.*;

/** Raw sockets exercise the production controller and the connector's blocking body reads. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN", "campus.imports.preview-work-ms=1500",
        "campus.imports.upload-read-ms=1000", "campus.imports.process-preview-count=2"})
@ActiveProfiles("test")
class ImportConfirmationTransportServerTest {
    private static final String COMMIT = "/api/application-imports/" + UUID.randomUUID() + "/commit";
    private static final Map<String, AtomicInteger> TOUCHES = new ConcurrentHashMap<>();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ImportPreviewService previews;
    @Autowired ImportWorkLimiter work;
    private record Session(String cookie, String token, UUID owner) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private Session session() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/test-confirmation-session")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var csrf = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Session(cookie, json.readTree(csrf.body()).get("token").asText(), UUID.fromString(response.body()));
    }
    private String headers(Session session, String path, String id, String token, String transport) {
        return "POST " + path + " HTTP/1.1\r\nHost: localhost\r\nCookie: " + session.cookie()
                + "\r\nX-Confirmation-Test: " + id + "\r\nContent-Type: application/json\r\n"
                + (token == null ? "" : "X-CSRF-TOKEN: " + token + "\r\n") + transport + "\r\n";
    }
    private Socket open(Session session, String path, String id, String token, String transport) throws IOException {
        Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(4500);
        socket.getOutputStream().write(headers(session, path, id, token, transport).getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush(); return socket;
    }
    private Socket pending(Session session, String path, String id) throws IOException {
        Socket socket = open(session, path, id, session.token(), "Content-Length: 4000\r\n");
        socket.getOutputStream().write(' '); socket.getOutputStream().flush(); return socket;
    }
    private String response(Socket socket) throws IOException {
        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
        var result = new StringBuilder();
        for (String line; (line = input.readLine()) != null && !line.isEmpty();) result.append(line).append('\n');
        return result.toString();
    }
    private HttpResponse<String> post(Session session, String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(5))
                .header("Cookie", session.cookie()).header("X-CSRF-TOKEN", session.token())
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private void awaitRead(String id, Socket socket) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (TOUCHES.getOrDefault(id, new AtomicInteger()).get() == 0 && System.nanoTime() < until) Thread.sleep(10);
        assertThat(TOUCHES.get(id)).isNotNull();
        if (TOUCHES.get(id).get() == 0) fail("Body not reached: " + response(socket));
    }
    private void awaitRelease(Session session) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            try { work.acquire(session.owner()); work.release(session.owner()); return; }
            catch (ApiException busy) { if (System.nanoTime() >= until) throw busy; Thread.sleep(10); }
        }
    }
    @Test void continuouslyDrippingConfirmationExpiresAndReleasesCapacity() throws Exception {
        var session = session(); String id = UUID.randomUUID().toString();
        try (Socket socket = open(session, COMMIT, id, session.token(), "Content-Length: 4000\r\n")) {
            var dripper = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        socket.getOutputStream().write(' '); socket.getOutputStream().flush(); Thread.sleep(100);
                    }
                } catch (IOException | InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            dripper.setDaemon(true); long started = System.nanoTime(); dripper.start();
            try {
                assertThat(response(socket)).contains("408").containsIgnoringCase("Cache-Control: no-store");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(3500));
            } finally { dripper.interrupt(); dripper.join(1000); }
        }
        awaitRelease(session); assertThat(post(session, COMMIT, "{}").statusCode()).isEqualTo(400);
    }
    @Test void ownerAndProcessAdmissionPrecedeBodyButStatusRemainsAvailable() throws Exception {
        var first = session(); var second = session(); var third = session();
        String one = UUID.randomUUID().toString(), two = UUID.randomUUID().toString();
        try (Socket held = pending(first, COMMIT, one)) {
            awaitRead(one, held);
            // Rate admission uses NOWAIT database locks; enter each body before opening the next.
            try (Socket heldSecond = pending(second, COMMIT, two)) {
                awaitRead(two, heldSecond);
                assertThat(post(first, COMMIT, "{}").statusCode()).isEqualTo(429);
                assertThat(post(third, COMMIT, "{}").statusCode()).isEqualTo(429);
                var status = client.send(HttpRequest.newBuilder(uri(COMMIT.replace("/commit", "")))
                        .header("Cookie", first.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
                assertThat(status.statusCode()).isEqualTo(404);
            }
        }
        awaitRelease(first); awaitRelease(second);
        assertThat(post(third, COMMIT, "{}").statusCode()).isEqualTo(400);
    }
    @Test void decodedCommitAliasesShareOwnerAdmissionWithPreview() throws Exception {
        for (String path : List.of(COMMIT.replace("application-imports", "%61pplication-imports"), COMMIT.replace("commit", "%63ommit"))) {
            var session = session(); String id = UUID.randomUUID().toString();
            try (Socket held = pending(session, path, id)) {
                awaitRead(id, held);
                var preview = client.send(HttpRequest.newBuilder(uri("/api/application-imports/preview"))
                        .header("Cookie", session.cookie()).header("X-CSRF-TOKEN", session.token())
                        .header("Content-Type", "multipart/form-data; boundary=b")
                        .POST(HttpRequest.BodyPublishers.ofString("--b--\r\n")).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(preview.statusCode()).isEqualTo(429);
            }
            awaitRelease(session);
        }
    }
    @Test void stalledAndDisconnectedConfirmationsReleaseCapacity() throws Exception {
        var session = session(); String id = UUID.randomUUID().toString();
        try (Socket socket = pending(session, COMMIT, id)) {
            awaitRead(id, socket); assertThat(response(socket)).contains("408");
        }
        awaitRelease(session);
        id = UUID.randomUUID().toString();
        try (Socket socket = pending(session, COMMIT, id)) { awaitRead(id, socket); }
        awaitRelease(session); assertThat(post(session, COMMIT, "{}").statusCode()).isEqualTo(400);
    }
    @Test void csrfSizeAndUnsupportedTransportRejectWithoutTouchingBody() throws Exception {
        var session = session();
        for (String token : Arrays.asList(null, " ", "invalid")) {
            String id = UUID.randomUUID().toString();
            try (Socket socket = open(session, COMMIT, id, token, "Content-Length: 4000\r\n")) {
                assertThat(response(socket)).contains("403"); assertThat(TOUCHES.get(id).get()).isZero();
            }
        }
        for (String transport : List.of("Content-Length: 4097\r\n", "Transfer-Encoding: chunked\r\n",
                "", "Content-Length: 4000\r\nContent-Encoding: gzip\r\n")) {
            String id = UUID.randomUUID().toString();
            try (Socket socket = open(session, COMMIT, id, session.token(), transport)) {
                assertThat(response(socket)).containsPattern("HTTP/1.1 (400|411|413)"); assertThat(TOUCHES.get(id).get()).isZero();
            }
        }
        awaitRelease(session);
    }
    @Test void anonymousCallerWithValidCsrfCannotReachConfirmationBody() throws Exception {
        var csrf = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        var anonymous = new Session(csrf.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0],
                json.readTree(csrf.body()).get("token").asText(), UUID.randomUUID());
        String id = UUID.randomUUID().toString();
        try (Socket socket = open(anonymous, COMMIT, id, anonymous.token(), "Content-Length: 4000\r\n")) {
            assertThat(response(socket)).contains("401"); assertThat(TOUCHES.get(id).get()).isZero();
        }
    }
    @Test void exactSizeConfirmationCommitsAndDuplicateReceiptAndStrictJsonRemainIntact() throws Exception {
        var session = session();
        Preview preview = previews.create(session.owner(), "Company,Role\nTransport,Intern\n".getBytes(StandardCharsets.UTF_8),
                json.writeValueAsString(new Options(1, Map.of("0", "company", "1", "role"), "ISO", List.of(), null)));
        String body = json.writeValueAsString(new CommitRequest(preview.reviewDigest(), new Acknowledgement(0)));
        String path = "/api/application-imports/" + preview.id() + "/commit";
        for (String invalid : List.of("{\"unknown\":true}", "{\"reviewDigest\":\"a\",\"reviewDigest\":\"b\"}", body + "{}")) {
            assertThat(post(session, path, invalid).statusCode()).isEqualTo(400); awaitRelease(session);
        }
        var committed = post(session, path, body + " ".repeat(4096 - body.length()));
        assertThat(committed.statusCode()).withFailMessage(committed.body()).isEqualTo(200);
        assertThat(post(session, path, body).body()).isEqualTo(committed.body()); awaitRelease(session);
    }
    @TestConfiguration static class Instrumentation {
        @Bean ServletRegistrationBean<HttpServlet> confirmationSession(UserRepository users) {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    var user = new CampusUser(UUID.randomUUID() + "@example.test", "Confirmation test", "unused");
                    user.verifyEmail(Instant.now()); users.saveAndFlush(user);
                    var principal = CampusPrincipal.from(user); var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
                    request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
                    response.getWriter().write(user.getId().toString());
                }
            }, "/test-confirmation-session");
        }
        @Bean FilterRegistrationBean<Filter> confirmationBodyRecorder() {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                var http = (HttpServletRequest) request; String id = http.getHeader("X-Confirmation-Test");
                if (id == null) { chain.doFilter(request, response); return; }
                var count = TOUCHES.computeIfAbsent(id, ignored -> new AtomicInteger());
                chain.doFilter(new HttpServletRequestWrapper(http) {
                    @Override public ServletInputStream getInputStream() throws IOException { count.incrementAndGet(); return super.getInputStream(); }
                    @Override public String getParameter(String key) { count.incrementAndGet(); return super.getParameter(key); }
                    @Override public Map<String, String[]> getParameterMap() { count.incrementAndGet(); return super.getParameterMap(); }
                    @Override public Collection<Part> getParts() throws IOException, ServletException { count.incrementAndGet(); return super.getParts(); }
                }, response);
            });
            registration.setOrder(Integer.MIN_VALUE + 20); return registration;
        }
    }
}
