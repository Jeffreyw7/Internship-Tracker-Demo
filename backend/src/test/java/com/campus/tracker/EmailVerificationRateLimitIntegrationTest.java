package com.campus.tracker;

import com.campus.tracker.mail.EmailOutboxWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Map;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "campus.verification.rate-limits-enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(EmailVerificationIntegrationTest.CaptureConfiguration.class)
class EmailVerificationRateLimitIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired EmailOutboxWorker worker;
    @Autowired EmailVerificationIntegrationTest.CapturingEmailSender mail;

    @BeforeEach void clearMail() { mail.clear(); }

    @Test void rejectedConfirmationsStillConsumeTheAttemptBudget() throws Exception {
        String email = UUID.randomUUID() + "@example.test";
        String password = EmailVerificationIntegrationTest.PASSWORD;
        mvc.perform(post("/api/auth/register").with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password,
                                "displayName", "Rate Limited", "includeSamples", false))))
                .andExpect(status().isAccepted());
        worker.deliverPending();
        String token = mail.tokenFor(email);
        String wrong = json.writeValueAsString(Map.of("token", token, "password", "Wrong-password-123!"));

        for (int attempt = 0; attempt < 10; attempt++) {
            mvc.perform(post("/api/auth/email-verification/confirm").with(csrf())
                            .contentType(MediaType.APPLICATION_JSON).content(wrong))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/email-verification/confirm").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(wrong))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }
}
