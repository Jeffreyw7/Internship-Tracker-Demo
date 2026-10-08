package com.campus.tracker;

import com.campus.tracker.auth.CampusPrincipal;
import com.campus.tracker.support.RedisFixture;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import static org.assertj.core.api.Assertions.*;

class DistributedSessionIT {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(3)).build();

    @Test void authenticationAndCsrfSurviveRoutingRestartAndLogout() throws Exception {
        // Baseline harness can demonstrate the container-session failure without Docker.
        boolean localBaseline = Boolean.getBoolean("campus.test.container-session-baseline");
        try (var redis = localBaseline ? null : new RedisFixture()) {
            String database = "jdbc:h2:mem:shared-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
            String namespace = "campus:test:session:" + UUID.randomUUID();
            var a = start(database, namespace, redis);
            try (var b = start(database, namespace, redis)) {
                try {
                    String baseA = base(a), baseB = base(b);
                    JsonNode csrf = json.readTree(request(baseA, "GET", "/api/auth/csrf", null, null, 200).body());
                    String oldToken = csrf.get("token").asText();
                    String email = "distributed-" + UUID.randomUUID() + "@example.test";
                    request(baseB, "POST", "/api/auth/register", json.writeValueAsString(Map.of("displayName", "Distributed",
                            "email", email, "password", "Campus-test-123!", "includeSamples", false)), oldToken, 202);
                    b.getBean(JdbcTemplate.class).update("UPDATE campus_users SET email_verified_at=CURRENT_TIMESTAMP WHERE email=?", email);
                    request(baseB, "POST", "/api/auth/login", "email=" + URLEncoder.encode(email, StandardCharsets.UTF_8)
                            + "&password=Campus-test-123%21", oldToken, 200);
                    String token = json.readTree(request(baseA, "GET", "/api/auth/csrf", null, null, 200).body()).get("token").asText();
                    assertThat(token).isNotEqualTo(oldToken);
                    request(baseA, "PUT", "/api/me/preferences", "{\"themePreference\":\"dark\"}", oldToken, 403);
                    request(baseB, "PUT", "/api/me/preferences", "{\"themePreference\":\"dark\"}", token, 200);
                    String draft = "{\"company\":\"Shared\",\"role\":\"Intern\",\"location\":\"Remote\",\"status\":\"Saved\",\"notes\":\"\",\"stages\":[]}";
                    var created = json.readTree(request(baseB, "POST", "/api/applications", draft, token, 201).body());
                    var updated = json.readTree(request(baseB, "PUT", "/api/applications/" + created.get("id").asText(),
                            draft.replace("Shared", "Updated shared"), token, 200, Map.of("If-Match", created.get("version").asText())).body());
                    assertThat(updated.get("version").asLong()).isGreaterThan(created.get("version").asLong());
                    request(baseA, "GET", "/api/applications", null, null, 200);
                    assertSessionHasNoHash(b, namespace);
                    a.close();
                    a = start(database, namespace, redis);
                    baseA = base(a);
                    request(baseA, "GET", "/api/auth/me", null, null, 200);
                    request(baseB, "POST", "/api/auth/logout", null, token, 204);
                    request(baseA, "GET", "/api/auth/me", null, null, 401);
                    if (System.getProperty("campus.test.redis.port") == null) {
                        // Even an existing anonymous session cookie must not make probes load Redis security state.
                        request(baseA, "GET", "/api/auth/csrf", null, null, 200);
                        var redisTemplate = a.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
                        redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                            connection.serverCommands().setConfig("maxmemory", "1"); return null;
                        });
                        try {
                            request(baseA, "GET", "/api/auth/csrf", null, null, 503);
                            request(baseA, "GET", "/livez", null, null, 200);
                        } finally {
                            redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
                                connection.serverCommands().setConfig("maxmemory", "67108864"); return null;
                            });
                        }
                        redis.stop();
                        request(baseA, "GET", "/api/auth/csrf", null, null, 503);
                        request(baseA, "GET", "/readyz", null, null, 503);
                        request(baseA, "GET", "/livez", null, null, 200);
                    }
                } finally { a.close(); }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void assertSessionHasNoHash(ServletWebServerApplicationContext context, String namespace) {
        var template = context.getBean(org.springframework.data.redis.core.StringRedisTemplate.class);
        // This namespace is unique to this disposable test, never shared with production data.
        var keys = template.keys(namespace + ":sessions:*");
        assertThat(keys).isNotEmpty();
        SessionRepository<Session> repository = (SessionRepository<Session>) context.getBean(SessionRepository.class);
        boolean found = false;
        for (String key : keys) {
            Session session = repository.findById(key.substring((namespace + ":sessions:").length()));
            if (session == null) continue;
            assertThat(session.getMaxInactiveInterval()).isEqualTo(Duration.ofHours(2));
            SecurityContext security = session.getAttribute("SPRING_SECURITY_CONTEXT");
            if (security == null) continue;
            found = true;
            assertThat(((CampusPrincipal) security.getAuthentication().getPrincipal()).passwordHash()).isNull();
            assertThat(template.opsForHash().values(key).toString()).doesNotContain("$2a$", "$2b$");
        }
        assertThat(found).isTrue();
    }

    private ServletWebServerApplicationContext start(String database, String namespace, RedisFixture redis) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(CampusApplication.class)
                .profiles(redis == null ? new String[]{"test"} : new String[]{"test", "distributed"})
                .run("--server.address=127.0.0.1", "--server.port=0", "--spring.datasource.url=" + database,
                        "--spring.session.redis.namespace=" + namespace,
                        "--spring.data.redis.host=" + (redis == null ? "127.0.0.1" : redis.host()),
                        "--spring.data.redis.port=" + (redis == null ? 1 : redis.port()),
                        "--campus.auth-admission.namespace=" + namespace + ":admission",
                        "--campus.auth-admission.hmac-secret=isolated-distributed-admission-secret-for-tests",
                        "--campus.mail.worker-enabled=false", "--campus.export.enabled=false", "--debug=false", "--logging.level.root=WARN");
    }
    private String base(ServletWebServerApplicationContext context) { return "http://127.0.0.1:" + context.getWebServer().getPort(); }
    private HttpResponse<String> request(String base, String method, String path, String body, String token, int expected) throws Exception {
        return request(base, method, path, body, token, expected, Map.of());
    }
    private HttpResponse<String> request(String base, String method, String path, String body, String token, int expected, Map<String, String> headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10));
        headers.forEach(builder::header);
        if (token != null) builder.header("X-CSRF-TOKEN", token);
        if (body != null) builder.header("Content-Type", path.equals("/api/auth/login")
                ? "application/x-www-form-urlencoded" : "application/json");
        var response = client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(method + " " + path + ": " + response.body()).isEqualTo(expected);
        return response;
    }
}
