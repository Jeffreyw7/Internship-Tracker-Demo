package com.campus.tracker;

import com.campus.tracker.auth.AuthDtos;
import com.campus.tracker.auth.verification.EmailVerificationController;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class SensitiveAuthDtoTest {
    @Test void authenticationRequestDiagnosticsNeverContainCredentialsTokensOrAddresses() {
        String password = "Never-log-this-123!";
        String token = "a".repeat(43);
        String email = "private-address@example.test";
        assertThat(new AuthDtos.Registration("Private Name", email, password, false).toString())
                .doesNotContain(password, email, "Private Name");
        assertThat(new EmailVerificationController.Confirm(token, password).toString())
                .doesNotContain(token, password);
        assertThat(new EmailVerificationController.Resend(email).toString())
                .doesNotContain(email);
    }
}
