package com.campus.tracker.mail;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** Claim only after a worker thread is free; SMTP runs outside the short database transaction. */
@Service
public class EmailOutboxWorker {
    private static final Logger log = LoggerFactory.getLogger(EmailOutboxWorker.class);
    private static final long[] RETRY_SECONDS = {60, 300, 900, 3600, 14400};
    private final OutboxClaimRepository claims;
    private final OutboxCrypto crypto;
    private final EmailSender sender;
    private final Clock clock;
    private final java.util.Optional<MailMetrics> metrics;

    public EmailOutboxWorker(JdbcTemplate jdbc, PlatformTransactionManager manager, OutboxCrypto crypto,
            EmailSender sender, Clock clock) {
        this(new OutboxClaimRepository(jdbc, manager, clock), crypto, sender, clock);
    }
    public EmailOutboxWorker(OutboxClaimRepository claims, OutboxCrypto crypto, EmailSender sender, Clock clock) {
        this(claims, crypto, sender, clock, java.util.Optional.empty());
    }
    @org.springframework.beans.factory.annotation.Autowired
    public EmailOutboxWorker(OutboxClaimRepository claims, OutboxCrypto crypto, EmailSender sender, Clock clock,
            java.util.Optional<MailMetrics> metrics) {
        this.claims = claims; this.crypto = crypto; this.sender = sender; this.clock = clock;
        this.metrics = metrics;
    }
    public void deliverPending() { for (int i = 0; i < 25 && deliverOne(); i++) { /* bounded local/manual convenience */ } }
    public boolean deliverOne() {
        var jobs = claims.claim(1, UUID.randomUUID());
        if (jobs.isEmpty()) return false;
        var job = jobs.get(0);
        if (!claims.eligible(job)) { claims.finish(job, "CANCELLED", "TOKEN_INELIGIBLE"); return true; }
        OutboxCrypto.Payload payload;
        try { payload = crypto.decrypt(job.id(), job.payload(), job.nonce(), job.keyId()); }
        catch (RuntimeException failure) {
            if (claims.finish(job, "FAILED", "PAYLOAD_INVALID")) metrics.ifPresent(MailMetrics::permanentFailure);
            return true;
        }
        long started = System.nanoTime();
        try {
            sender.send(payload.recipient(), payload.token());
            if (claims.finish(job, "SENT", null)) {
                metrics.ifPresent(MailMetrics::accepted);
                log.info("Verification mail accepted job={} elapsedMs={}", job.id(), (System.nanoTime() - started) / 1_000_000);
            }
        } catch (PermanentDeliveryException failure) {
            if (claims.finish(job, "FAILED", "RECIPIENT_REJECTED")) {
                metrics.ifPresent(MailMetrics::permanentFailure);
                log.error("Verification mail terminal failure job={} category=RECIPIENT_REJECTED", job.id());
            }
        } catch (RuntimeException failure) {
            if (job.attempts() >= 6) {
                if (claims.finish(job, "FAILED", "DELIVERY_EXHAUSTED")) metrics.ifPresent(MailMetrics::permanentFailure);
            }
            else {
                var next = clock.instant().atOffset(ZoneOffset.UTC).plusSeconds(Math.round(RETRY_SECONDS[job.attempts() - 1]
                        * ThreadLocalRandom.current().nextDouble(0.8, 1.2)));
                if (claims.retry(job, next)) {
                    metrics.ifPresent(MailMetrics::retry);
                    log.warn("Verification mail retry job={} attempt={} category=PROVIDER_TEMPORARY", job.id(), job.attempts());
                }
            }
        }
        return true;
    }
}
