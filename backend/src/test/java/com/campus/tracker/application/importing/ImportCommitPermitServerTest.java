package com.campus.tracker.application.importing;

import com.campus.tracker.auth.*;
import com.campus.tracker.common.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.*;
import org.springframework.aop.support.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.web.servlet.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.*;
import static com.campus.tracker.application.importing.ImportDtos.*;
import static org.assertj.core.api.Assertions.*;

/** Releasing a confirmation permit after parsing, rather than transaction completion, must fail these tests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "debug=false", "logging.level.root=WARN", "logging.level.org.springframework=WARN",
        "spring.datasource.url=jdbc:h2:mem:import-permit-server;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "campus.imports.process-preview-count=1", "campus.imports.cleanup-enabled=false"})
@ActiveProfiles("test")
class ImportCommitPermitServerTest {
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired ImportPreviewService previews;
    @Autowired ImportPreviewRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Coordination coordination;
    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(2)).build();

    private record Session(String cookie, String token, UUID owner) {}
    private record ApplicationRow(UUID id, String company, String role, long version) {}
    private record Snapshot(boolean transactionActive, String transactionName, ImportPreviewRepository.Stored stored,
                            List<ApplicationRow> applications, long reservedReceiptBytes) {}
    private record Pending(String id, Observation observation, CompletableFuture<HttpResponse<String>> response) {}

    @Test void fullyReadConfirmationHoldsOwnerAndProcessPermitsUntilCommit() throws Exception {
        exerciseTransaction(false);
    }

    @Test void fullyReadConfirmationHoldsPermitsThroughRollbackAndSameConfirmationCanRetry() throws Exception {
        exerciseTransaction(true);
    }

    private void exerciseTransaction(boolean rollback) throws Exception {
        Session owner = session(), other = session();
        Preview original = preview(owner, "First,Engineer\nSecond,Analyst\n");
        Preview next = preview(owner, "Later,Intern\n");
        Preview otherPreview = preview(other, "Other,Intern\n");
        Snapshot before = snapshot(owner, original);
        assertThat(before.transactionActive()).isFalse();
        assertThat(before.stored().state()).isEqualTo("PREVIEW");
        assertThat(before.stored().payload()).isNotEmpty();
        assertThat(before.stored().payloadBytes()).isEqualTo(before.stored().payload().getBytes(StandardCharsets.UTF_8).length);
        assertThat(before.reservedReceiptBytes()).isEqualTo(131072);
        assertThat(before.applications()).isEmpty();

        String confirmation = confirmation(original);
        Gate gate = new Gate(original.id(), rollback, () -> snapshot(owner, original));
        assertThat(coordination.gate.compareAndSet(null, gate)).isTrue();
        Pending pending = null;
        try {
            pending = startCommit(owner, original, confirmation);
            Snapshot inTransaction = gate.entered.get(5, TimeUnit.SECONDS);
            assertThat(inTransaction.transactionActive()).isTrue();
            assertThat(inTransaction.transactionName()).isEqualTo(ImportCommitService.class.getName() + ".commit");
            assertThat(inTransaction.stored().state()).isEqualTo("COMPLETED");
            assertThat(inTransaction.stored().payload()).isNull();
            assertThat(inTransaction.stored().payloadBytes()).isZero();
            assertThat(inTransaction.stored().receipt()).isNotEmpty();
            assertThat(inTransaction.stored().receiptBytes()).isPositive();
            assertThat(inTransaction.applications()).extracting(ApplicationRow::company).containsExactly("First", "Second");
            assertThat(inTransaction.applications()).extracting(ApplicationRow::role).containsExactly("Engineer", "Analyst");
            assertThat(pending.observation().declaredLength).isEqualTo(confirmation.getBytes(StandardCharsets.UTF_8).length);
            assertThat(pending.observation().readBytes.get()).isEqualTo(pending.observation().declaredLength);
            assertThat(pending.observation().finished.getCount()).isEqualTo(1);
            assertThat(gate.completed).isNotDone();
            assertThat(pending.response()).isNotDone();

            // A separate connection cannot see either the flushed applications or the receipt yet.
            assertThat(snapshot(owner, original)).isEqualTo(before);
            assertBusy(commit(owner, next, confirmation(next)));
            assertBusy(commit(other, otherPreview, confirmation(otherPreview)));
            Status live = status(owner, original);
            assertThat(live.status()).isEqualTo("PREVIEW");
            assertThat(live.preview()).isEqualTo(original);
            assertThat(live.receipt()).isNull();
            assertThat(gate.completed).isNotDone();

            gate.release.countDown();
            HttpResponse<String> response = await(pending);
            assertThat(gate.completed.get(3, TimeUnit.SECONDS)).isEqualTo(rollback
                    ? TransactionSynchronization.STATUS_ROLLED_BACK : TransactionSynchronization.STATUS_COMMITTED);
            Receipt receipt;
            if (rollback) {
                assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(409);
                assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("TEST_FORCED_ROLLBACK");
                // Exact original canonical bytes and PREVIEW reservation survive the failed transaction.
                assertThat(snapshot(owner, original)).isEqualTo(before);
                Status retryable = status(owner, original);
                assertThat(retryable.status()).isEqualTo("PREVIEW");
                assertThat(retryable.receipt()).isNull();
                receipt = receipt(commit(owner, original, confirmation));
            } else {
                receipt = receipt(response);
                assertThat(receipt).isEqualTo(json.readValue(inTransaction.stored().receipt(), Receipt.class));
            }
            assertCompleted(owner, original, receipt);
            assertThat(receipt(commit(owner, original, confirmation))).isEqualTo(receipt);
            assertThat(snapshot(owner, original).applications()).hasSize(2);
            // Both the owner slot and the single process slot must become usable by real writes.
            assertThat(receipt(commit(owner, next, confirmation(next))).counts().added()).isEqualTo(1);
            assertThat(receipt(commit(other, otherPreview, confirmation(otherPreview))).counts().added()).isEqualTo(1);
            assertThat(snapshot(owner, original).applications()).hasSize(3);
            assertThat(snapshot(other, otherPreview).applications()).hasSize(1);
        } finally {
            gate.release.countDown();
            try {
                if (pending != null) await(pending);
            } finally {
                coordination.gate.compareAndSet(gate, null);
                if (pending != null) {
                    if (!pending.response().isDone()) pending.response().cancel(true);
                    coordination.requests.remove(pending.id());
                }
            }
        }
    }

    private Snapshot snapshot(Session owner, Preview preview) {
        var stored = repository.find(owner.owner(), preview.id(), false);
        var rows = jdbc.query("select id,company,role,version from applications where owner_id=? order by company",
                (rs, n) -> new ApplicationRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getLong(4)), owner.owner());
        long reservation = jdbc.queryForObject("select case when state='PREVIEW' then 131072 else receipt_bytes end from application_imports where id=?",
                Long.class, preview.id());
        return new Snapshot(TransactionSynchronizationManager.isActualTransactionActive(),
                TransactionSynchronizationManager.getCurrentTransactionName(), stored, rows, reservation);
    }

    private void assertCompleted(Session owner, Preview original, Receipt receipt) throws Exception {
        assertThat(receipt.importId()).isEqualTo(original.id());
        assertThat(receipt.receiptId()).isNotNull();
        assertThat(receipt.counts()).isEqualTo(new Counts(2, 0, 0, 0, 0));
        assertThat(receipt.rows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.action()).isEqualTo("Add");
            assertThat(row.applicationId()).isNotNull();
            assertThat(row.resultVersion()).isZero();
        });
        Snapshot saved = snapshot(owner, original);
        assertThat(saved.stored().state()).isEqualTo("COMPLETED");
        assertThat(saved.stored().payload()).isNull();
        assertThat(saved.stored().payloadBytes()).isZero();
        assertThat(json.readValue(saved.stored().receipt(), Receipt.class)).isEqualTo(receipt);
        assertThat(saved.stored().receiptBytes()).isEqualTo(saved.stored().receipt().getBytes(StandardCharsets.UTF_8).length);
        assertThat(saved.stored().completed()).isEqualTo(receipt.completedAt());
        assertThat(saved.stored().retain()).isEqualTo(receipt.retainUntil());
        assertThat(saved.applications()).extracting(ApplicationRow::id)
                .containsExactlyInAnyOrderElementsOf(receipt.rows().stream().map(ReceiptRow::applicationId).toList());
        Status completed = status(owner, original);
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(completed.receipt()).isEqualTo(receipt);
        assertThat(completed.preview()).isNull();
    }

    private Session session() throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri("/test-permit-session")).timeout(Duration.ofSeconds(3)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        String cookie = response.headers().firstValue("set-cookie").orElseThrow().split(";", 2)[0];
        var csrf = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).timeout(Duration.ofSeconds(3))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(csrf.statusCode()).isEqualTo(200);
        return new Session(cookie, json.readTree(csrf.body()).get("token").asText(), UUID.fromString(response.body()));
    }

    private Preview preview(Session owner, String rows) throws Exception {
        return previews.create(owner.owner(), ("Company,Role\n" + rows).getBytes(StandardCharsets.UTF_8),
                json.writeValueAsString(new Options(1, Map.of("0", "company", "1", "role"), "ISO", List.of(), null)));
    }
    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }
    private String path(Preview preview) { return "/api/application-imports/" + preview.id(); }
    private String confirmation(Preview preview) throws Exception {
        return json.writeValueAsString(new CommitRequest(preview.reviewDigest(), new Acknowledgement(0)));
    }
    private Pending startCommit(Session owner, Preview preview, String body) {
        String id = UUID.randomUUID().toString();
        Observation observation = new Observation(); coordination.requests.put(id, observation);
        var request = HttpRequest.newBuilder(uri(path(preview) + "/commit")).timeout(Duration.ofSeconds(20))
                .header("Cookie", owner.cookie()).header("X-CSRF-TOKEN", owner.token()).header("X-Permit-Test", id)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return new Pending(id, observation, client.sendAsync(request, HttpResponse.BodyHandlers.ofString()));
    }
    private HttpResponse<String> commit(Session owner, Preview preview, String body) throws Exception {
        Pending pending = startCommit(owner, preview, body);
        try { return await(pending); }
        finally {
            if (!pending.response().isDone()) pending.response().cancel(true);
            coordination.requests.remove(pending.id());
        }
    }
    private HttpResponse<String> await(Pending pending) throws Exception {
        var response = pending.response().get(5, TimeUnit.SECONDS);
        // Delivery may precede ImportRequestFilter.finally; the outer observer establishes release.
        assertThat(pending.observation().finished.await(3, TimeUnit.SECONDS)).isTrue();
        return response;
    }
    private Status status(Session owner, Preview preview) throws Exception {
        var response = client.send(HttpRequest.newBuilder(uri(path(preview))).timeout(Duration.ofSeconds(2))
                .header("Cookie", owner.cookie()).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readValue(response.body(), Status.class);
    }
    private void assertBusy(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(429);
        assertThat(response.headers().firstValue("content-type").orElse("")).contains("application/json");
        assertThat(json.readTree(response.body()).path("code").asText()).isEqualTo("IMPORT_BUSY");
    }
    private Receipt receipt(HttpResponse<String> response) throws Exception {
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(200);
        return json.readValue(response.body(), Receipt.class);
    }

    static final class Observation {
        volatile long declaredLength = -1;
        final AtomicLong readBytes = new AtomicLong();
        final CountDownLatch finished = new CountDownLatch(1);
    }
    static final class Coordination {
        final AtomicReference<Gate> gate = new AtomicReference<>();
        final Map<String, Observation> requests = new ConcurrentHashMap<>();
    }
    static final class Gate {
        final UUID previewId;
        final boolean rollback;
        final Supplier<Snapshot> inspect;
        final AtomicBoolean registered = new AtomicBoolean();
        final CompletableFuture<Snapshot> entered = new CompletableFuture<>();
        final CountDownLatch release = new CountDownLatch(1);
        final CompletableFuture<Integer> completed = new CompletableFuture<>();
        Gate(UUID previewId, boolean rollback, Supplier<Snapshot> inspect) {
            this.previewId = previewId; this.rollback = rollback; this.inspect = inspect;
        }
        void register() {
            if (!TransactionSynchronizationManager.isActualTransactionActive())
                throw new IllegalStateException("Real import transaction required");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void beforeCommit(boolean readOnly) {
                    try {
                        entered.complete(inspect.get());
                        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Import test gate timed out");
                        if (rollback) throw new ApiException(HttpStatus.CONFLICT, "TEST_FORCED_ROLLBACK", "Test rollback after real writes.");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); throw new IllegalStateException(e);
                    } catch (RuntimeException | Error e) {
                        entered.completeExceptionally(e); throw e;
                    }
                }
                @Override public void afterCompletion(int status) { completed.complete(status); }
            });
        }
    }

    @TestConfiguration static class Instrumentation {
        @Bean @Role(BeanDefinition.ROLE_INFRASTRUCTURE) static Coordination permitCoordination() { return new Coordination(); }
        @Bean static BeanPostProcessor receiptTransactionGate(Coordination coordination) {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof ImportPreviewRepository)) return bean;
                    var pointcut = new StaticMethodMatcherPointcut() {
                        @Override public boolean matches(java.lang.reflect.Method method, Class<?> targetClass) {
                            return method.getName().equals("complete") && method.getParameterCount() == 3;
                        }
                    };
                    var advisor = new DefaultPointcutAdvisor(pointcut, (MethodInterceptor) invocation -> {
                        Object result = invocation.proceed(); // The real receipt UPDATE has already executed.
                        Gate gate = coordination.gate.get();
                        var stored = (ImportPreviewRepository.Stored) invocation.getArguments()[0];
                        if (gate != null && gate.previewId.equals(stored.id()) && gate.registered.compareAndSet(false, true)) gate.register();
                        return result;
                    });
                    if (bean instanceof Advised advised) { advised.addAdvisor(0, advisor); return bean; }
                    var proxy = new ProxyFactory(bean); proxy.setProxyTargetClass(true); proxy.addAdvisor(advisor);
                    return proxy.getProxy();
                }
            };
        }
        @Bean ServletRegistrationBean<HttpServlet> permitSession(UserRepository users) {
            return new ServletRegistrationBean<>(new HttpServlet() {
                @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                    var user = new CampusUser(UUID.randomUUID() + "@example.test", "Permit test", "unused");
                    user.verifyEmail(Instant.now()); users.saveAndFlush(user);
                    var principal = CampusPrincipal.from(user); var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
                    request.getSession().setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
                    response.getWriter().write(user.getId().toString());
                }
            }, "/test-permit-session");
        }
        @Bean FilterRegistrationBean<Filter> permitRequestObserver(Coordination coordination) {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                var http = (HttpServletRequest) request;
                String id = http.getHeader("X-Permit-Test");
                Observation observation = id == null ? null : coordination.requests.get(id);
                if (observation == null) { chain.doFilter(request, response); return; }
                observation.declaredLength = http.getContentLengthLong();
                try {
                    chain.doFilter(new HttpServletRequestWrapper(http) {
                        private ServletInputStream recorded;
                        @Override public ServletInputStream getInputStream() throws IOException {
                            if (recorded != null) return recorded;
                            ServletInputStream source = super.getInputStream();
                            recorded = new ServletInputStream() {
                                @Override public int read() throws IOException {
                                    int value = source.read(); if (value >= 0) observation.readBytes.incrementAndGet(); return value;
                                }
                                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                                    int count = source.read(bytes, offset, length); if (count > 0) observation.readBytes.addAndGet(count); return count;
                                }
                                @Override public boolean isFinished() { return source.isFinished(); }
                                @Override public boolean isReady() { return source.isReady(); }
                                @Override public void setReadListener(ReadListener listener) { source.setReadListener(listener); }
                                @Override public void close() throws IOException { source.close(); }
                            };
                            return recorded;
                        }
                    }, response);
                } finally { observation.finished.countDown(); }
            });
            registration.setOrder(Integer.MIN_VALUE + 20); return registration;
        }
    }
}
