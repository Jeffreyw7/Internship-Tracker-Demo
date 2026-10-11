package com.campus.tracker;

import com.campus.tracker.auth.verification.AuthRateLimiter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class RecoveryRateLimiterTest {
    private JdbcTemplate jdbc;
    private AuthRateLimiter limiter;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:recovery-rate-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        limiter = new AuthRateLimiter(jdbc, Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC), "test-rate-limit-secret-long-enough", false);
    }
    @Test void recoveryLimitsRemainEnabledWhenVerificationLimitsAreDisabled() {
        assertThat(limiter.reserveResetAddress("student@example.test")).isTrue();
        assertThat(limiter.reserveResetAddress("student@example.test")).isFalse();
        for(int i=0;i<5;i++) limiter.requireResetRequestAllowed("192.0.2.1");
        assertThatThrownBy(() -> limiter.requireResetRequestAllowed("192.0.2.1"))
                .isInstanceOf(com.campus.tracker.auth.abuse.AuthThrottleException.class);
        assertThat(jdbc.queryForList("SELECT subject_hash FROM auth_rate_limits", String.class))
                .allSatisfy(value -> assertThat(value).hasSize(64).doesNotContain("student", "192.0.2"));
    }
    @Test void confirmationGlobalSaturationDoesNotAllocateRandomTokenSubjects() {
        for(int i=0;i<60;i++) limiter.requireResetConfirmationAllowed("token-"+i, "192.0.2."+i);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limits", Integer.class);
        assertThatThrownBy(() -> limiter.requireResetConfirmationAllowed("new-token", "192.0.2.99")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM auth_rate_limits", Integer.class)).isEqualTo(before);
    }
    @Test void sharedLinkCeilingCannotBeBypassedByDisabledVerificationLimits() {
        assertThat(limiter.reserveEmail("student@example.test")).isTrue();
        assertThat(limiter.reserveLinkMail()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth_rate_limits WHERE scope='mail-global-day'", Integer.class)).isEqualTo(2);
        assertThat(limiter.reserveNoticeMail()).isTrue();
        assertThat(jdbc.queryForObject("SELECT attempts FROM auth_rate_limits WHERE scope='reset-notice-day'", Integer.class)).isEqualTo(1);
    }
    @Test void overlappingQuotaKeysCannotResetAnExistingAddressBudget() {
        var old = new AuthRateLimiter(jdbc, Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC), "previous-rate-limit-secret-long-enough", false);
        assertThat(old.reserveResetAddress("student@example.test")).isTrue();
        var rotated = new AuthRateLimiter(jdbc, Clock.fixed(Instant.parse("2026-10-11T00:00:00Z"), ZoneOffset.UTC),
                "test-rate-limit-secret-long-enough", false, List.of("previous-rate-limit-secret-long-enough"));
        assertThat(rotated.reserveResetAddress("student@example.test")).isFalse();
    }
}
