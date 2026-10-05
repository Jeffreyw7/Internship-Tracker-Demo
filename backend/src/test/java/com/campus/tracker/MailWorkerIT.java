package com.campus.tracker;

import com.campus.tracker.mail.OutboxClaimRepository;
import com.campus.tracker.migration.MigrationMain;
import java.time.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

class MailWorkerIT {
    @Test void restrictedWorkersSkipLockedJobsAndFenceExpiredOwners() throws Exception {
        try (var database = new PostgresPrivilegesIT.TestDatabase(false)) {
            var provision = database.psql("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", database.getUsername(), "-d", "campus", "-f", "provision.sql");
            assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
            MigrationMain.run(Map.of("MIGRATION_DATABASE_URL", database.getJdbcUrl(), "MIGRATION_DATABASE_USERNAME", "campus_migrator",
                    "MIGRATION_DATABASE_PASSWORD", "migration-'quote-password"));
            var source = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var admin = new JdbcTemplate(source);
            var clock = new MutableClock();
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            var user = UUID.randomUUID(); var token = UUID.randomUUID();
            admin.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", user, "worker@example.test", "Test", "synthetic", now);
            admin.update("INSERT INTO email_verification_tokens(id,user_id,email,token_hash,created_at,expires_at) VALUES(?,?,?,?,?,?)", token, user, "worker@example.test", "synthetic-hash", now, now.plusDays(1));
            var locked = UUID.randomUUID(); var available = UUID.randomUUID();
            for (var id : java.util.List.of(locked, available)) admin.update("INSERT INTO email_outbox(id,verification_token_id,payload,nonce,key_id,status,next_attempt_at,created_at) VALUES(?,?,?,?,?,'PENDING',?,?)",
                    id, token, "synthetic-encrypted", "synthetic-nonce", "synthetic-key", now, now);
            var workerSource = new DriverManagerDataSource(database.getJdbcUrl(), "campus_mail_worker", "mail-worker-'quote-password");
            var workerJdbc = new JdbcTemplate(workerSource);
            var first = new OutboxClaimRepository(workerJdbc, new DataSourceTransactionManager(workerSource), clock);
            var second = new OutboxClaimRepository(workerJdbc, new DataSourceTransactionManager(workerSource), clock);
            try (var connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM email_outbox WHERE id=? FOR UPDATE")) {
                    statement.setObject(1, locked); statement.executeQuery().close();
                }
                // A regression to ordinary FOR UPDATE must fail promptly instead of hanging this check.
                workerJdbc.setQueryTimeout(2);
                long start = System.nanoTime();
                var claimed = second.claim(2, UUID.randomUUID());
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(2));
                assertThat(claimed).extracting(OutboxClaimRepository.ClaimedMail::id).containsExactly(available);
                assertThat(second.eligible(claimed.get(0))).isTrue();
                connection.rollback();
            }
            var old = first.claim(2, UUID.randomUUID());
            assertThat(old).extracting(OutboxClaimRepository.ClaimedMail::id).containsExactly(locked);
            assertThat(second.claim(2, UUID.randomUUID())).isEmpty();
            clock.advance(Duration.ofSeconds(121));
            assertThat(first.finish(old.get(0), "SENT", null)).isFalse();
            var replacements = second.claim(2, UUID.randomUUID());
            assertThat(replacements).hasSize(2);
            assertThat(first.retry(old.get(0), now.plusMinutes(10))).isFalse();
            for (var job : replacements) assertThat(second.finish(job, "SENT", null)).isTrue();
            assertThat(admin.queryForList("SELECT payload FROM email_outbox", String.class)).containsOnlyNulls();
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM campus_users", Integer.class)).isEqualTo(1);
            assertThat(admin.queryForObject("SELECT consumed_at FROM email_verification_tokens", OffsetDateTime.class)).isNull();
        }
    }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-04T10:00:00Z");
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
