package com.campus.tracker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.assertj.core.api.Assertions.assertThat;

class TrustedProxyIntegrationTest {
    @Test void untrustedForwardedHeadersCannotChangeAdmissionIdentity() throws Exception {
        try (var server = start("^$")) {
            assertThat(csrf(server, "198.51.100.1")).isEqualTo(200);
            assertThat(csrf(server, "198.51.100.2")).isEqualTo(200);
            assertThat(csrf(server, "198.51.100.3")).isEqualTo(429);
        }
    }
    @Test void trustedIngressDistinguishesClientsAndDoesNotTrustSpoofedLeftmostHop() throws Exception {
        try (var server = start("127\\.0\\.0\\.1")) {
            assertThat(csrf(server, "198.51.100.20")).isEqualTo(200);
            assertThat(csrf(server, "198.51.100.20")).isEqualTo(200);
            assertThat(csrf(server, "203.0.113.90, 198.51.100.20")).isEqualTo(429);
            assertThat(csrf(server, "198.51.100.21")).isEqualTo(200);
            // Shared NAT still shares an IP budget; changing a cookie is not an IP exemption.
            assertThat(csrf(server, "198.51.100.20")).isEqualTo(429);
        }
    }
    private ServletWebServerApplicationContext start(String trusted) {
        return (ServletWebServerApplicationContext) new SpringApplicationBuilder(CampusApplication.class).profiles("test").run(
                "--server.port=0", "--server.address=127.0.0.1", "--server.forward-headers-strategy=NATIVE",
                "--server.tomcat.remoteip.internal-proxies=" + trusted,
                "--server.tomcat.remoteip.trusted-proxies=^$",
                "--spring.datasource.url=jdbc:h2:mem:proxy-" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
                "--campus.auth-abuse.csrf-ip.capacity=2", "--campus.auth-abuse.csrf-ip.refill-period=1h",
                "--debug=false", "--logging.level.root=WARN");
    }
    private int csrf(ServletWebServerApplicationContext server, String forwarded) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                + server.getWebServer().getPort() + "/api/auth/csrf")).header("X-Forwarded-For", forwarded).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
