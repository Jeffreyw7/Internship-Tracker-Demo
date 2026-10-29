package com.campus.tracker.mail;

import com.campus.tracker.auth.verification.AuthRateLimiter;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import jakarta.annotation.PreDestroy;

/** Short fenced leases surround SMTP; no database transaction remains open during delivery. */
@Service
public class EmailOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);
    private static final long[] RETRY_SECONDS = {60, 300, 900, 3600, 14400};
    private static final String VERIFICATION_ELIGIBLE = """
            EXISTS (SELECT 1 FROM email_verification_tokens t JOIN campus_users u ON u.id=t.user_id
              WHERE t.id=email_outbox.verification_token_id AND t.email=u.email AND t.expires_at>?
                AND t.consumed_at IS NULL AND t.revoked_at IS NULL
                AND u.email_verified_at IS NULL AND u.email_verification_exempt=false)
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final OutboxCrypto crypto;
    private final EmailSender sender;
    private final Clock clock;
    private final AuthRateLimiter limiter;
    private final Semaphore deliveries = new Semaphore(2);
    private boolean primaryTime;
    private volatile Instant lastPoll;
    private volatile boolean stopping;

    public EmailOutboxWorker(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxCrypto crypto,
                             EmailSender sender, Clock clock) {
        this(jdbc, manager, crypto, sender, clock, null);
    }
    public EmailOutboxWorker(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxCrypto crypto,
                             EmailSender sender, Clock clock, AuthRateLimiter limiter) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.transactions.setTimeout(5);
        this.crypto = crypto; this.sender = sender; this.clock = clock; this.limiter = limiter;
    }
    @Autowired
    public EmailOutboxWorker(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxCrypto crypto,
                             EmailSender sender, Clock clock, AuthRateLimiter limiter, Environment environment) {
        this(jdbc, manager, crypto, sender, clock, limiter);
        primaryTime = environment.acceptsProfiles(Profiles.of("prod"));
    }
    public Instant lastPollAt() { return lastPoll; }
    @PreDestroy public void stop() { stopping = true; }
    public void deliverPending() {
        if (stopping || !deliveries.tryAcquire()) return;
        try {
            lastPoll = now().toInstant();
            cleanupVerification();
            var now = now();
            var ids = jdbc.queryForList("""
                    SELECT id FROM email_outbox WHERE
                      (message_type<>'PASSWORD_RESET_LINK' OR EXISTS(SELECT 1 FROM password_reset_control WHERE id=1 AND enabled=true))
                      AND ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                    ORDER BY next_attempt_at,id LIMIT 25
                    """, UUID.class, now, now);
            for (UUID id : ids) { if(stopping) break; deliver(id); }
        } finally { deliveries.release(); }
    }
    private void deliver(UUID id) {
        UUID owner = UUID.randomUUID();
        Job job = transactions.execute(status -> claim(id, owner));
        if (job == null) return;
        if (job.type() == EmailMessage.Type.PASSWORD_RESET_LINK && !resetEnabled()) { pauseLink(id, owner); return; }
        if (!eligible(job, owner)) { finish(id, owner, terminalState(job), "TOKEN_INELIGIBLE"); return; }
        if (job.type() == EmailMessage.Type.PASSWORD_CHANGED && limiter != null && !limiter.reserveNoticeMail()) {
            deferNotice(id, owner); return;
        }
        OutboxCrypto.MessagePayload payload;
        try { payload = crypto.decrypt(id, job.type(), job.payload(), job.nonce(), job.keyId()); }
        catch (RuntimeException exception) { finish(id, owner, "FAILED", "PAYLOAD_INVALID"); return; }
        // Recheck after decryption/quota work, immediately before transport.
        if (job.type() == EmailMessage.Type.PASSWORD_RESET_LINK && !resetEnabled()) { pauseLink(id, owner); return; }
        if (!eligible(job, owner)) { finish(id, owner, terminalState(job), "TOKEN_INELIGIBLE"); return; }
        long started = System.nanoTime();
        try {
            sender.send(new EmailMessage(id, job.type(), payload.recipient(), payload.token(),
                    payload.occurredAt() == null ? null : Instant.parse(payload.occurredAt())));
            finish(id, owner, "SENT", null);
            log.info("Mail accepted purpose={} job={} elapsedMs={}", job.type(), id, (System.nanoTime()-started)/1_000_000);
        } catch (PermanentDeliveryException exception) { finish(id, owner, "FAILED", "RECIPIENT_REJECTED"); }
        catch (RuntimeException exception) { retry(job, owner); }
    }
    private Job claim(UUID id, UUID owner) {
        var now = now();
        int changed = jdbc.update("""
                UPDATE email_outbox SET status='SENDING',lease_owner=?,lease_expires_at=?,attempts=attempts+1
                WHERE id=? AND attempts<CASE WHEN message_type='EMAIL_VERIFICATION' THEN 6 ELSE 2 END
                  AND (message_type<>'PASSWORD_RESET_LINK' OR EXISTS(SELECT 1 FROM password_reset_control WHERE id=1 AND enabled=true))
                  AND ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                """, owner, now.plusMinutes(2), id, now, now);
        if (changed == 0) return null;
        return jdbc.queryForObject("SELECT id,message_type,payload,nonce,key_id,attempts,created_at FROM email_outbox WHERE id=? AND lease_owner=?",
                (rs, row) -> new Job(rs.getObject("id", UUID.class), EmailMessage.Type.valueOf(rs.getString("message_type")),
                        rs.getString("payload"), rs.getString("nonce"), rs.getString("key_id"), rs.getInt("attempts"),
                        rs.getTimestamp("created_at").toInstant()), id, owner);
    }
    private boolean eligible(Job job, UUID owner) {
        var now = now();
        String base = "SELECT COUNT(*) FROM email_outbox WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>? AND ";
        return switch(job.type()) {
            case EMAIL_VERIFICATION -> jdbc.queryForObject(base + VERIFICATION_ELIGIBLE, Integer.class, job.id(), owner, now, now) == 1;
            case PASSWORD_RESET_LINK -> jdbc.queryForObject(base + """
                    EXISTS(SELECT 1 FROM password_reset_tokens t JOIN campus_users u ON u.id=t.user_id
                      WHERE t.id=email_outbox.password_reset_token_id AND t.email=u.email AND t.credential_version=u.credential_version
                        AND t.expires_at>? AND t.consumed_at IS NULL AND t.revoked_at IS NULL)
                    """, Integer.class, job.id(), owner, now, now) == 1;
            case PASSWORD_CHANGED -> job.createdAt().plus(Duration.ofHours(24)).isAfter(now.toInstant())
                    && jdbc.queryForObject(base + """
                    EXISTS(SELECT 1 FROM password_reset_tokens t JOIN campus_users u ON u.id=t.user_id
                      WHERE t.id=email_outbox.password_reset_token_id AND t.email=u.email AND t.consumed_at IS NOT NULL)
                    """, Integer.class, job.id(), owner, now) == 1;
        };
    }
    private String terminalState(Job job) { return job.type() == EmailMessage.Type.PASSWORD_CHANGED ? "FAILED" : "CANCELLED"; }
    private boolean resetEnabled() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_control WHERE id=1 AND enabled=true", Integer.class) == 1;
    }
    private void pauseLink(UUID id, UUID owner) {
        var now = now();
        jdbc.update("""
                UPDATE email_outbox SET status='PENDING',attempts=attempts-1,next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL
                WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, now.plusSeconds(5), id, owner, now);
    }
    private void deferNotice(UUID id, UUID owner) {
        var now = now();
        var tomorrow = now.toLocalDate().plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);
        jdbc.update("""
                UPDATE email_outbox SET status='PENDING',attempts=attempts-1,next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,failure_category='NOTICE_BUDGET'
                WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, tomorrow, id, owner, now);
    }
    private void retry(Job job, UUID owner) {
        int maximum = job.type() == EmailMessage.Type.EMAIL_VERIFICATION ? 6 : 2;
        if (job.attempts() >= maximum) { finish(job.id(), owner, "FAILED", "DELIVERY_EXHAUSTED"); return; }
        var now = now();
        var next = now.plusSeconds(Math.round(RETRY_SECONDS[job.attempts()-1]*ThreadLocalRandom.current().nextDouble(0.8,1.2)));
        jdbc.update("""
                UPDATE email_outbox SET status='PENDING',next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,failure_category='PROVIDER_TEMPORARY'
                WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, next, job.id(), owner, now);
    }
    private void finish(UUID id, UUID owner, String state, String category) {
        var now = now();
        int changed = jdbc.update("""
                UPDATE email_outbox SET status=?,payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,
                  failure_category=?,sent_at=?,next_attempt_at=? WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, state, category, "SENT".equals(state) ? now : null, now, id, owner, now);
        if(changed>0 && "FAILED".equals(state)) log.error("Mail terminal failure job={} category={}", id, category);
    }
    // Recovery maintenance is separate; legacy cleanup must never delete another purpose's rows.
    private void cleanupVerification() {
        var now = now();
        var invalid = jdbc.queryForList("SELECT id FROM email_outbox WHERE message_type='EMAIL_VERIFICATION' AND status IN ('PENDING','SENDING') AND NOT "
                + VERIFICATION_ELIGIBLE + " ORDER BY id LIMIT 1000", UUID.class, now);
        for(UUID id : invalid) jdbc.update("""
                UPDATE email_outbox SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,
                  next_attempt_at=?,failure_category='TOKEN_INELIGIBLE' WHERE id=? AND message_type='EMAIL_VERIFICATION' AND status IN ('PENDING','SENDING')
                """, now, id);
        var exhausted = jdbc.queryForList("""
                SELECT id FROM email_outbox WHERE attempts>=CASE WHEN message_type='EMAIL_VERIFICATION' THEN 6 ELSE 2 END
                  AND ((status='SENDING' AND lease_expires_at<=?) OR status='PENDING') ORDER BY id LIMIT 1000
                """, UUID.class, now);
        for(UUID id : exhausted) jdbc.update("""
                UPDATE email_outbox SET status='FAILED',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,
                  next_attempt_at=?,failure_category='DELIVERY_EXHAUSTED' WHERE id=?
                  AND attempts>=CASE WHEN message_type='EMAIL_VERIFICATION' THEN 6 ELSE 2 END
                  AND ((status='SENDING' AND lease_expires_at<=?) OR status='PENDING')
                """, now, id, now);
        var cutoff = now.minusDays(7);
        for(UUID id : jdbc.queryForList("SELECT id FROM email_outbox WHERE message_type='EMAIL_VERIFICATION' AND status IN ('SENT','CANCELLED','FAILED') AND next_attempt_at<? ORDER BY id LIMIT 1000", UUID.class, cutoff))
            jdbc.update("DELETE FROM email_outbox WHERE id=? AND message_type='EMAIL_VERIFICATION' AND status IN ('SENT','CANCELLED','FAILED') AND next_attempt_at<?", id, cutoff);
        for(UUID id : jdbc.queryForList("SELECT id FROM email_verification_tokens WHERE expires_at<? AND NOT EXISTS(SELECT 1 FROM email_outbox o WHERE o.verification_token_id=email_verification_tokens.id) ORDER BY id LIMIT 1000", UUID.class, cutoff))
            jdbc.update("DELETE FROM email_verification_tokens WHERE id=? AND expires_at<? AND NOT EXISTS(SELECT 1 FROM email_outbox o WHERE o.verification_token_id=email_verification_tokens.id)", id, cutoff);
        jdbc.update("DELETE FROM auth_rate_limits WHERE expires_at<? AND (scope,subject_hash,window_start) IN (SELECT scope,subject_hash,window_start FROM auth_rate_limits WHERE expires_at<? LIMIT 1000)", now, now);
    }
    private OffsetDateTime now() {
        Instant instant = primaryTime ? jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant()) : clock.instant();
        return instant.atOffset(ZoneOffset.UTC);
    }
    private record Job(UUID id, EmailMessage.Type type, String payload, String nonce, String keyId, int attempts, Instant createdAt) { }
}
