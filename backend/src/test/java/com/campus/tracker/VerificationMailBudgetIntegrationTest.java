package com.campus.tracker;

import com.campus.tracker.auth.verification.AuthRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:configurable-mail-budget;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "campus.verification.rate-limits-enabled=true", "campus.verification.global-mail-daily-limit=2"})
@ActiveProfiles("test")
class VerificationMailBudgetIntegrationTest {
    @Autowired AuthRateLimiter limiter;
    @Test void configurableDailyBudgetIsSharedAcrossDifferentRecipients() {
        assertThat(limiter.reserveEmail(UUID.randomUUID() + "@example.test")).isTrue();
        assertThat(limiter.reserveEmail(UUID.randomUUID() + "@example.test")).isTrue();
        assertThat(limiter.reserveEmail(UUID.randomUUID() + "@example.test")).isFalse();
    }
}
