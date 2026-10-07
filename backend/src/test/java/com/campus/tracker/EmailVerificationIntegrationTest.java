package com.campus.tracker;

import com.campus.tracker.mail.EmailOutboxWorker;
import com.campus.tracker.mail.EmailSender;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.CaptureConfiguration.class)
class EmailVerificationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EmailOutboxWorker worker;
    @Autowired CapturingEmailSender mail;
    static final String PASSWORD = "Verification-test-123!";

    @BeforeEach void clearMail() { mail.clear(); }

    @Test void newAccountCannotSignInBeforeVerification() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD,
                        "displayName", "New User", "includeSamples", false))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("VERIFICATION_REQUEST_ACCEPTED"));
        mvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", PASSWORD))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("EMAIL_VERIFICATION_REQUIRED"));
        mvc.perform(get("/api/applications")).andExpect(status().isUnauthorized());
    }

    @Test void confirmationRequiresCsrfAndSignupPasswordThenAllowsOrdinaryLogin() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        register(email);
        worker.deliverPending();
        String token = mail.tokenFor(email);

        String correct = json.writeValueAsString(Map.of("token", token, "password", PASSWORD));
        mvc.perform(post("/api/auth/email-verification/confirm").contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/email-verification/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "password", "Wrong-password-123!"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VERIFICATION_INVALID"));
        mvc.perform(post("/api/auth/email-verification/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EMAIL_VERIFIED"));
        mvc.perform(post("/api/auth/email-verification/confirm").with(csrf()).contentType(MediaType.APPLICATION_JSON).content(correct))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VERIFICATION_INVALID"));
        mvc.perform(post("/api/auth/login").with(csrf()).param("email", email).param("password", PASSWORD))
                .andExpect(status().isOk()).andExpect(jsonPath("$.email").value(email));
    }

    @Test void registrationRejectsAttemptsToSetTheServerOwnedExemption() throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", UUID.randomUUID() + "@example.test",
                                "password", PASSWORD, "displayName", "Forged", "includeSamples", false,
                                "emailVerificationExempt", true))))
                .andExpect(status().isBadRequest());
    }

    @Test void resendResponseDoesNotRevealWhetherAnAccountExists() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String unknown = mvc.perform(post("/api/auth/email-verification/resend").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        register(email);
        String pending = mvc.perform(post("/api/auth/email-verification/resend").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email", email))))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(json.readTree(pending)).isEqualTo(json.readTree(unknown));
    }

    @Test void concurrentConfirmationActivatesTheAccountOnlyOnce() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        register(email);
        worker.deliverPending();
        String token = mail.tokenFor(email);
        String body = json.writeValueAsString(Map.of("token", token, "password", PASSWORD));
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> confirm = () -> {
            start.await();
            return mvc.perform(post("/api/auth/email-verification/confirm").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andReturn().getResponse().getStatus();
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(confirm);
            var second = pool.submit(confirm);
            start.countDown();
            org.assertj.core.api.Assertions.assertThat(java.util.List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
    }

    private void register(String email) throws Exception {
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", PASSWORD,
                                "displayName", "New User", "includeSamples", false))))
                .andExpect(status().isAccepted());
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class CaptureConfiguration {
        @Bean @Primary CapturingEmailSender capturingEmailSender() { return new CapturingEmailSender(); }
    }

    static class CapturingEmailSender implements EmailSender {
        private final Map<String, String> tokens = new ConcurrentHashMap<>();
        @Override public void send(String recipient, String token) { tokens.put(recipient, token); }
        String tokenFor(String email) { return java.util.Objects.requireNonNull(tokens.get(email), "No captured mail for " + email); }
        void clear() { tokens.clear(); }
    }
}
