package com.campus.tracker;

import com.campus.tracker.auth.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:concurrent-registration;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConcurrentRegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean PasswordEncoder passwords;

    @Test void simultaneousDuplicateSignupsAreAcceptedAndCreateOneCompleteAccount() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String password = "Concurrent-test-123!";
        var barrier = new CyclicBarrier(2);
        // Pause at hashing, after both real existence checks but before either insert.
        doAnswer(call -> {
            if (password.contentEquals(call.getArgument(0, CharSequence.class))) barrier.await(10, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(passwords).encode(any(CharSequence.class));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> signup(email, password, "192.0.2.101"));
            var second = pool.submit(() -> signup(email, password, "192.0.2.102"));
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(202);
            assertThat(second.get(20, TimeUnit.SECONDS)).isEqualTo(202);
            var user = users.findByEmail(email).orElseThrow();
            assertThat(passwords.matches(password, user.getPasswordHash())).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_users WHERE email=?", Long.class, email)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM applications WHERE owner_id=?", Long.class, user.getId())).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens WHERE user_id=?", Long.class, user.getId())).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE verification_token_id IN "
                + "(SELECT id FROM email_verification_tokens WHERE user_id=?)", Long.class, user.getId())).isEqualTo(1);
        } finally {pool.shutdownNow();}
    }

    private int signup(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/auth/register").with(csrf()).with(r -> {r.setRemoteAddr(ip); return r;})
            .contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"%s","password":"%s","displayName":"Concurrent","includeSamples":true}
                """.formatted(email, password)))
            .andReturn().getResponse().getStatus();
    }
}
