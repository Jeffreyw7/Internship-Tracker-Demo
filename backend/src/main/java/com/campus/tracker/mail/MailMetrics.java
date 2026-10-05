package com.campus.tracker.mail;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Scrapes read a cached snapshot; SQL aggregation runs only on the worker schedule. */
@Component
@ConditionalOnProperty(name = "campus.mail.worker-enabled", havingValue = "true", matchIfMissing = true)
public class MailMetrics {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Counter retries, permanentFailures, accepted;
    private volatile double backlog, oldestAge;

    public MailMetrics(JdbcTemplate jdbc, MeterRegistry registry, Clock clock) {
        this.jdbc = jdbc; this.clock = clock;
        Gauge.builder("campus.mail.backlog", this, value -> value.backlog).register(registry);
        Gauge.builder("campus.mail.oldest.age", this, value -> value.oldestAge).baseUnit("seconds").register(registry);
        retries = registry.counter("campus.mail.retries");
        permanentFailures = registry.counter("campus.mail.permanent.failures");
        accepted = registry.counter("campus.mail.accepted");
    }
    @Scheduled(fixedDelayString = "${campus.mail.metrics-interval-ms:60000}", initialDelay = 1000)
    public void refresh() {
        jdbc.query("SELECT COUNT(*), MIN(created_at) FROM email_outbox WHERE status IN ('PENDING','SENDING')", row -> {
            backlog = row.getLong(1);
            var oldest = row.getObject(2, OffsetDateTime.class);
            oldestAge = oldest == null ? 0 : Math.max(0, java.time.Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
        });
    }
    public void retry() { retries.increment(); }
    public void permanentFailure() { permanentFailures.increment(); }
    public void accepted() { accepted.increment(); }
}
