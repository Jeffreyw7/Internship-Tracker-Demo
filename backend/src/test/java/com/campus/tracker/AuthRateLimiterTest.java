package com.campus.tracker;

import com.campus.tracker.auth.verification.AuthRateLimiter;
import com.campus.tracker.common.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AuthRateLimiterTest {
    @Test void addressBudgetsAreBoundedAndIdentifiersAreNotStoredInPlaintext() {
        MutableClock clock = new MutableClock();
        var source = new DriverManagerDataSource("jdbc:h2:mem:rate-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        AuthRateLimiter limiter = new AuthRateLimiter(jdbc, clock,
                "test-rate-limit-secret-long-enough", true);

        assertThat(limiter.reserveEmail("student@example.test")).isTrue();
        assertThat(limiter.reserveEmail("student@example.test")).isFalse();
        assertThat(jdbc.queryForList("SELECT subject_hash FROM auth_rate_limits", String.class))
                .allSatisfy(value -> assertThat(value).doesNotContain("student", "example.test").hasSize(64));

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.reserveEmail("student@example.test")).isTrue();
        for (int i = 0; i < 5; i++) limiter.requireRequestAllowed("192.0.2.10");
        assertThatThrownBy(() -> limiter.requireRequestAllowed("192.0.2.10"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getStatus().value()).isEqualTo(429);
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-09-25T00:00:00Z");
        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
