package com.campus.tracker.mail;

import java.time.*;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Short atomic leases surround SMTP calls; no database transaction remains open during delivery. */
@Service
public class EmailOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);
    private static final long[] RETRY_SECONDS = {60, 300, 900, 3600, 14400};
    private static final String ELIGIBLE = """
            EXISTS (SELECT 1 FROM email_verification_tokens t JOIN campus_users u ON u.id=t.user_id
                WHERE t.id=email_outbox.verification_token_id AND t.email=u.email
                  AND t.expires_at>? AND t.consumed_at IS NULL AND t.revoked_at IS NULL
                  AND u.email_verified_at IS NULL AND u.email_verification_exempt=false)
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OutboxCrypto crypto;
    private final EmailSender sender;
    private final Clock clock;

    public EmailOutboxWorker(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxCrypto crypto,
                             EmailSender sender, Clock clock) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager);
        this.crypto = crypto; this.sender = sender; this.clock = clock;
    }

    public void deliverPending() {
        cleanup();
        var now = now();
        var ids = jdbc.queryForList("""
                SELECT id FROM email_outbox WHERE attempts<6 AND
                ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                ORDER BY next_attempt_at,id LIMIT 25
                """, UUID.class, now, now);
        for (UUID id : ids) {
            UUID owner = UUID.randomUUID();
            Job job = transactions.execute(status -> claim(id, owner));
            if (job == null) continue;
            // Confirmation and resend can run between claiming and SMTP; recheck as late as possible.
            if (jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE id=? AND lease_owner=? AND " + ELIGIBLE,
                    Integer.class, id, owner, now()) == 0) {
                finish(id, owner, "CANCELLED", null); continue;
            }
            OutboxCrypto.Payload payload;
            try {
                payload = crypto.decrypt(id, job.payload(), job.nonce(), job.keyId());
            } catch (RuntimeException exception) {
                finish(id, owner, "FAILED", "PAYLOAD_INVALID"); continue;
            }
            long started = System.nanoTime();
            try {
                sender.send(payload.recipient(), payload.token());
                finish(id, owner, "SENT", null);
                log.info("Verification mail accepted job={} elapsedMs={}", id, (System.nanoTime() - started) / 1_000_000);
            } catch (PermanentDeliveryException exception) {
                finish(id, owner, "FAILED", "RECIPIENT_REJECTED");
            } catch (RuntimeException exception) {
                retry(job, owner);
            }
        }
        Integer aged = jdbc.queryForObject("""
                SELECT COUNT(*) FROM email_outbox WHERE status IN ('PENDING','SENDING') AND created_at<?
                """, Integer.class, now().minusMinutes(5));
        if (aged != null && aged > 0) log.warn("Verification mail queue delayed count={} thresholdMinutes=5", aged);
    }

    private Job claim(UUID id, UUID owner) {
        var now = now();
        int changed = jdbc.update("""
                UPDATE email_outbox SET status='SENDING',lease_owner=?,lease_expires_at=?,attempts=attempts+1
                WHERE id=? AND attempts<6 AND
                ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                """, owner, now.plusMinutes(2), id, now, now);
        if (changed == 0) return null;
        return jdbc.queryForObject("SELECT id,payload,nonce,key_id,attempts FROM email_outbox WHERE id=? AND lease_owner=?",
                (row, index) -> new Job(row.getObject("id", UUID.class), row.getString("payload"), row.getString("nonce"),
                        row.getString("key_id"), row.getInt("attempts")), id, owner);
    }

    private void retry(Job job, UUID owner) {
        if (job.attempts() >= 6) { finish(job.id(), owner, "FAILED", "DELIVERY_EXHAUSTED"); return; }
        var next = now().plusSeconds(Math.round(RETRY_SECONDS[job.attempts() - 1] * ThreadLocalRandom.current().nextDouble(0.8, 1.2)));
        int changed = jdbc.update("""
                UPDATE email_outbox SET status='PENDING',next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,failure_category='PROVIDER_TEMPORARY'
                WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, next, job.id(), owner, now());
        if (changed > 0) log.warn("Verification mail retry job={} attempt={} category=PROVIDER_TEMPORARY", job.id(), job.attempts());
    }

    private void finish(UUID id, UUID owner, String state, String category) {
        var now = now();
        int changed = jdbc.update("""
                UPDATE email_outbox SET status=?,payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,
                    failure_category=?,sent_at=?,next_attempt_at=? WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, state, category, "SENT".equals(state) ? now : null, now, id, owner, now);
        if (changed > 0 && "FAILED".equals(state)) log.error("Verification mail terminal failure job={} category={}", id, category);
    }

    private void cleanup() {
        transactions.executeWithoutResult(status -> {
            var now = now();
            jdbc.update("""
                    UPDATE email_outbox SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,
                        lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=?,failure_category='TOKEN_INELIGIBLE'
                    WHERE status IN ('PENDING','SENDING') AND NOT
                    """ + ELIGIBLE, now, now);
            int exhausted = jdbc.update("""
                    UPDATE email_outbox SET status='FAILED',payload=NULL,key_id=NULL,nonce=NULL,
                        lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=?,failure_category='DELIVERY_EXHAUSTED'
                    WHERE attempts>=6 AND ((status='SENDING' AND lease_expires_at<=?) OR status='PENDING')
                    """, now, now);
            if (exhausted > 0) log.error("Verification mail abandoned after final lease count={} category=DELIVERY_EXHAUSTED", exhausted);
            var cutoff = now.minusDays(7);
            jdbc.update("DELETE FROM email_outbox WHERE status IN ('SENT','CANCELLED','FAILED') AND next_attempt_at<?", cutoff);
            jdbc.update("""
                    DELETE FROM email_verification_tokens WHERE expires_at<? AND
                    NOT EXISTS (SELECT 1 FROM email_outbox o WHERE o.verification_token_id=email_verification_tokens.id)
                    """, cutoff);
            jdbc.update("DELETE FROM auth_rate_limits WHERE expires_at<?", now);
        });
    }

    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
    private record Job(UUID id, String payload, String nonce, String keyId, int attempts) { }
}
