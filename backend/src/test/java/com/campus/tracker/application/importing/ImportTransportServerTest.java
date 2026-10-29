package com.campus.tracker.application.importing;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN"})
@ActiveProfiles("test")
class ImportTransportServerTest {
    private static final AtomicReference<CountDownLatch> UPLOAD_ENTERED = new AtomicReference<>();
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    private record Session(String cookie, String token) {}
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private Session session() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/test-import-session")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var csrf = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new Session(cookie, json.readTree(csrf.body()).get("token").asText());
    }
    private String multipart() {
        return "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"ignored.csv\"\r\nContent-Type: text/csv\r\n\r\nCompany,Role\nA,B\r\n--boundary--\r\n";
    }
    @Test void realMultipartUsesBoundedReaderAndNoStore() throws Exception {
        var session = session();
        var response = client.send(HttpRequest.newBuilder(uri("/api/application-imports/preview"))
                .header("Cookie", session.cookie()).header("X-CSRF-TOKEN", session.token())
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofString(multipart())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("Company,Role\nA,B");
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
    }
    @Test void missingAndInvalidCsrfRejectWithoutWaitingForAdvertisedBody() throws Exception {
        var session = session();
        for (String token : new String[]{"", "X-CSRF-TOKEN: invalid\r\n"}) {
            String result = rawHeaders("POST /api/application-imports/preview HTTP/1.1\r\nHost: localhost\r\nCookie: " + session.cookie()
                    + "\r\nContent-Type: multipart/form-data; boundary=boundary\r\nContent-Length: 100000\r\n" + token + "\r\n");
            assertThat(result).contains("403").containsIgnoringCase("Cache-Control: no-store");
        }
    }
    @Test void unsupportedChunkedAndAbsentLengthRejectBeforeBody() throws Exception {
        var session = session();
        for (String transport : new String[]{"Transfer-Encoding: chunked\r\n", "", "Content-Length: 4000000\r\n", "Content-Length: 1000\r\nContent-Encoding: gzip\r\n"}) {
            String response = rawHeaders("POST /api/application-imports/preview HTTP/1.1\r\nHost: localhost\r\nCookie: " + session.cookie()
                    + "\r\nX-CSRF-TOKEN: " + session.token() + "\r\nContent-Type: multipart/form-data; boundary=boundary\r\n" + transport + "\r\n");
            assertThat(response).containsPattern("HTTP/1.1 (400|411|413)").containsIgnoringCase("Cache-Control: no-store");
        }
    }
    @Test void stalledBodyTimesOutAndReleasesOwnerCapacity() throws Exception {
        var session = session();
        try (Socket socket = openUpload(session)) {
            long started = System.nanoTime();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            assertThat(reader.readLine()).contains("408");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        }
        assertValidUpload(session);
    }
    @Test void concurrentSameOwnerUploadIsRejectedWhileFirstBodyIsStillPending() throws Exception {
        var session = session();
        var entered = new CountDownLatch(1); UPLOAD_ENTERED.set(entered);
        try (Socket held = openUpload(session)) {
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            var response = client.send(HttpRequest.newBuilder(uri("/api/application-imports/preview"))
                    .header("Cookie", session.cookie()).header("X-CSRF-TOKEN", session.token())
                    .header("Content-Type", "multipart/form-data; boundary=boundary")
                    .POST(HttpRequest.BodyPublishers.ofString(multipart())).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(429);
            assertThat(response.headers().firstValue("Retry-After")).isPresent();
        } finally { UPLOAD_ENTERED.set(null); }
    }
    @Test void continuouslyDrippingBodyCannotRetainCapacityBeyondAbsoluteDeadline() throws Exception {
        var session = session();
        try (Socket socket = openUpload(session)) {
            Thread dripper = new Thread(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        socket.getOutputStream().write('x'); socket.getOutputStream().flush(); Thread.sleep(250);
                    }
                } catch (IOException | InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            dripper.setDaemon(true); dripper.start();
            long started = System.nanoTime();
            try {
                var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                assertThat(reader.readLine()).contains("408");
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(35));
            } finally { dripper.interrupt(); }
        }
        assertValidUpload(session);
    }
    private Socket openUpload(Session session) throws Exception {
        Socket socket = new Socket("127.0.0.1", port); socket.setSoTimeout(36000);
        socket.getOutputStream().write(("POST /api/application-imports/preview HTTP/1.1\r\nHost: localhost\r\nCookie: " + session.cookie()
                + "\r\nX-CSRF-TOKEN: " + session.token() + "\r\nContent-Type: multipart/form-data; boundary=boundary\r\nContent-Length: 100000\r\n\r\n"
                + "--boundary\r\nContent-Disposition: form-data; name=\"file\"\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush(); return socket;
    }
    private void assertValidUpload(Session session) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/api/application-imports/preview"))
                .header("Cookie", session.cookie()).header("X-CSRF-TOKEN", session.token())
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(HttpRequest.BodyPublishers.ofString(multipart())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }
    private String rawHeaders(String headers) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(3000);
            socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            var reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
            var result = new StringBuilder();
            for (String line; (line = reader.readLine()) != null && !line.isEmpty();) result.append(line).append('\n');
            return result.toString();
        }
    }
    @TestConfiguration
    static class Servlets {
        @Bean ServletRegistrationBean<HttpServlet> importSessionServlet(UserRepository users) {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    var user = new CampusUser(UUID.randomUUID() + "@example.test", "Transport test", "unused");
                    user.verifyEmail(java.time.Instant.now()); users.saveAndFlush(user);
                    var principal = CampusPrincipal.from(user);
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
                    request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
                    response.getWriter().write("ok");
                }
            }, "/test-import-session");
        }
        @Bean ServletRegistrationBean<HttpServlet> importPreviewServlet(ImportMultipartReader reader) {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    CountDownLatch entered = UPLOAD_ENTERED.get();
                    if (entered != null) entered.countDown();
                    var upload = reader.read(request);
                    response.getOutputStream().write(upload.file());
                }
            }, "/api/application-imports/preview");
        }
    }
}
