package com.campus.tracker.mail;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class EmailOutboxWorkerTest {
    private JdbcTemplate jdbc;
    private TransactionTemplate transactions;
    private OutboxCrypto crypto;
    private EmailOutbox outbox;
    private EmailOutboxWorker worker;
    private final MutableClock clock = new MutableClock();
    private final List<String> deliveries = new ArrayList<>();
    private RuntimeException failure;

    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:mail-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        crypto = new OutboxCrypto(OutboxCryptoTest.properties(), new ObjectMapper());
        outbox = new EmailOutbox(jdbc, crypto, clock);
        worker = new EmailOutboxWorker(jdbc, transactions.getTransactionManager(), crypto, (email, token) -> {
            if (failure != null) throw failure;
            deliveries.add(email + ":" + token);
        }, clock);
    }

    @Test void sendsCommittedJobAndErasesAllRecoverableSecrets() {
        UUID token = queue();
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox", String.class)).doesNotContain("synthetic", "student");
        worker.deliverPending();
        assertThat(deliveries).containsExactly("student@example.test:synthetic-token");
        assertThat(state()).isEqualTo("SENT");
        assertErased();
        worker.deliverPending();
        assertThat(deliveries).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT consumed_at FROM email_verification_tokens WHERE id=?", OffsetDateTime.class, token)).isNull();
    }

    @Test void rollbackRemovesDeliveryIntent() {
        transactions.executeWithoutResult(status -> { queue(); status.setRollbackOnly(); });
        worker.deliverPending();
        assertThat(deliveries).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox", Integer.class)).isZero();
    }

    @Test void retriesTransientFailureThenSucceedsWithSameToken() {
        queue(); failure = new IllegalStateException("Do not log synthetic-secret");
        worker.deliverPending();
        assertThat(state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT next_attempt_at FROM email_outbox", OffsetDateTime.class).toInstant())
                .isBetween(clock.instant().plusSeconds(48), clock.instant().plusSeconds(72));
        worker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_outbox", Integer.class)).isEqualTo(1);
        clock.advance(Duration.ofMinutes(2)); failure = null; worker.deliverPending();
        assertThat(state()).isEqualTo("SENT");
        assertThat(deliveries).containsExactly("student@example.test:synthetic-token");
    }

    @Test void stopsAfterSixFailuresAndErasesPayload() {
        queue(); failure = new IllegalStateException("provider failure");
        for (int i = 0; i < 6; i++) { worker.deliverPending(); clock.advance(Duration.ofHours(5)); }
        assertThat(state()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_outbox", Integer.class)).isEqualTo(6);
        assertErased();
    }

    @Test void permanentRecipientFailureIsTerminalImmediately() {
        queue(); failure = new PermanentDeliveryException(); worker.deliverPending();
        assertThat(state()).isEqualTo("FAILED"); assertErased();
    }

    @Test void cancelsExpiredConsumedRevokedVerifiedExemptAndMismatchedAddresses() {
        for (String mutation : List.of(
                "UPDATE email_verification_tokens SET expires_at=created_at",
                "UPDATE email_verification_tokens SET consumed_at=created_at",
                "UPDATE email_verification_tokens SET revoked_at=created_at",
                "UPDATE campus_users SET email_verified_at=created_at",
                "UPDATE campus_users SET email_verification_exempt=true",
                "UPDATE campus_users SET email='changed@example.test'")) {
            jdbc.update("DELETE FROM email_outbox"); jdbc.update("DELETE FROM email_verification_tokens"); jdbc.update("DELETE FROM campus_users");
            queue(); jdbc.update(mutation); worker.deliverPending();
            assertThat(state()).as(mutation).isEqualTo("CANCELLED"); assertErased();
        }
        assertThat(deliveries).isEmpty();
    }

    @Test void onlyExpiredLeasesCanBeTakenOverAfterRestart() {
        queue();
        jdbc.update("UPDATE email_outbox SET status='SENDING',attempts=1,lease_owner=?,lease_expires_at=?", UUID.randomUUID(), at(clock.instant().plusSeconds(120)));
        worker.deliverPending(); assertThat(deliveries).isEmpty();
        clock.advance(Duration.ofSeconds(121)); worker.deliverPending();
        assertThat(state()).isEqualTo("SENT");
        assertThat(jdbc.queryForObject("SELECT attempts FROM email_outbox", Integer.class)).isEqualTo(2);
    }

    @Test void corruptPayloadBecomesTerminalWithoutSending() {
        queue(); jdbc.update("UPDATE email_outbox SET payload='invalid'"); worker.deliverPending();
        assertThat(state()).isEqualTo("FAILED"); assertErased(); assertThat(deliveries).isEmpty();
    }

    @Test void leaseLossDuringSendCannotOverwriteNewOwnersOutcome() {
        queue();
        var racing = new EmailOutboxWorker(jdbc, transactions.getTransactionManager(), crypto, (email, token) -> {
            jdbc.update("UPDATE email_outbox SET lease_owner=?, status='SENDING'", UUID.randomUUID());
        }, clock);
        racing.deliverPending();
        assertThat(state()).isEqualTo("SENDING");
        assertThat(jdbc.queryForObject("SELECT payload FROM email_outbox", String.class)).isNotNull();
    }

    @Test void secondWorkerCannotSendWhileAnotherLeaseIsActive() {
        queue();
        var first = new EmailOutboxWorker(jdbc, transactions.getTransactionManager(), crypto, (email, token) -> {
            worker.deliverPending();
            deliveries.add("first-worker:" + token);
        }, clock);
        first.deliverPending();
        assertThat(deliveries).containsExactly("first-worker:synthetic-token");
        assertThat(state()).isEqualTo("SENT");
    }

    @Test void cleanupRemovesTerminalMetadataAndOldChallengesWithoutDeletingUsers() {
        queue();
        jdbc.update("INSERT INTO auth_rate_limits(scope,subject_hash,window_start,attempts,expires_at) VALUES(?,?,?,?,?)",
                "request-minute", "expired", at(clock.instant()), 1, at(clock.instant().plusSeconds(30)));
        jdbc.update("INSERT INTO auth_rate_limits(scope,subject_hash,window_start,attempts,expires_at) VALUES(?,?,?,?,?)",
                "request-minute", "live", at(clock.instant()), 1, at(clock.instant().plus(Duration.ofDays(20))));
        worker.deliverPending(); clock.advance(Duration.ofDays(10)); worker.deliverPending();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM email_verification_tokens", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT subject_hash FROM auth_rate_limits", String.class)).containsExactly("live");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM campus_users", Integer.class)).isEqualTo(1);
    }

    private UUID queue() {
        UUID user = UUID.randomUUID(), token = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", user, "student@example.test", "Student", "test-only", at(clock.instant()));
        jdbc.update("INSERT INTO email_verification_tokens(id,user_id,email,token_hash,created_at,expires_at) VALUES(?,?,?,?,?,?)", token, user, "student@example.test", UUID.randomUUID().toString(), at(clock.instant()), at(clock.instant().plus(Duration.ofDays(2))));
        outbox.enqueue(token, "student@example.test", "synthetic-token"); return token;
    }

    private String state() { return jdbc.queryForObject("SELECT status FROM email_outbox", String.class); }
    private void assertErased() {
        assertThat(jdbc.queryForMap("SELECT payload,key_id,nonce,lease_owner,lease_expires_at FROM email_outbox").values()).containsOnlyNulls();
    }
    private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T00:00:00Z");
        void advance(Duration amount) { now = now.plus(amount); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
