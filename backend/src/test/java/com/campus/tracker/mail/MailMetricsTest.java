package com.campus.tracker.mail;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MailMetricsTest {
    @Test void snapshotsQueueWithoutRecipientLabelsAndCountsOutcomes() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:metrics-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        var jdbc = new JdbcTemplate(source);
        var registry = new SimpleMeterRegistry();
        var now = Instant.parse("2026-10-04T10:00:00Z");
        var metrics = new MailMetrics(jdbc, registry, Clock.fixed(now, ZoneOffset.UTC));
        var user = UUID.randomUUID(); var token = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", user, "metrics@example.test", "Test", "synthetic", now.atOffset(ZoneOffset.UTC));
        jdbc.update("INSERT INTO email_verification_tokens(id,user_id,email,token_hash,created_at,expires_at) VALUES(?,?,?,?,?,?)", token, user, "metrics@example.test", "synthetic-hash", now.atOffset(ZoneOffset.UTC), now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
        jdbc.update("INSERT INTO email_outbox(id,verification_token_id,status,next_attempt_at,created_at) VALUES(?,?,'PENDING',?,?)", UUID.randomUUID(), token, now.atOffset(ZoneOffset.UTC), now.minusSeconds(90).atOffset(ZoneOffset.UTC));
        metrics.refresh(); metrics.retry(); metrics.permanentFailure(); metrics.accepted();
        assertThat(registry.get("campus.mail.backlog").gauge().value()).isEqualTo(1);
        assertThat(registry.get("campus.mail.oldest.age").gauge().value()).isEqualTo(90);
        assertThat(registry.get("campus.mail.retries").counter().count()).isEqualTo(1);
        assertThat(registry.get("campus.mail.permanent.failures").counter().count()).isEqualTo(1);
        assertThat(registry.get("campus.mail.accepted").counter().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter -> assertThat(meter.getId().getTags()).isEmpty());
    }
}
