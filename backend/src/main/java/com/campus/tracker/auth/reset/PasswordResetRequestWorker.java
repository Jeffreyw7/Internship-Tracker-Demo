package com.campus.tracker.auth.reset;

import com.campus.tracker.auth.UserRepository;
import com.campus.tracker.auth.verification.AuthRateLimiter;
import com.campus.tracker.mail.OutboxCrypto;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.campus.tracker.auth.reset.PasswordResetService.stamp;

/** Fenced intake leases end before acquiring account locks; no email leaves this process here. */
@Service
public class PasswordResetRequestWorker {
    private final JdbcTemplate jdbc;
    private final DatabaseTime time;
    private final ResetControl control;
    private final RecoveryCapacity capacity;
    private final OutboxCrypto crypto;
    private final UserRepository users;
    private final AuthRateLimiter limits;
    private final PasswordResetService resets;
    private final TransactionTemplate transaction;
    private final Semaphore workers=new Semaphore(2);
    private volatile long lastHealthy=System.nanoTime();
    private volatile boolean failed;
    private volatile boolean stopping;
    private static final long[] RETRY={1,5,15,60};

    public PasswordResetRequestWorker(JdbcTemplate jdbc,DatabaseTime time,ResetControl control,RecoveryCapacity capacity,
            OutboxCrypto crypto,UserRepository users,AuthRateLimiter limits,PasswordResetService resets,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.time=time;this.control=control;this.capacity=capacity;this.crypto=crypto;this.users=users;this.limits=limits;this.resets=resets;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);transaction.setTimeout(5);
    }
    public boolean healthy() { return !failed && System.nanoTime()-lastHealthy<TimeUnit.SECONDS.toNanos(120); }
    @jakarta.annotation.PreDestroy public void stop() { stopping=true; }
    public void processPending() {
        if (stopping || !workers.tryAcquire()) return;
        try (var database=capacity.database()) {
            if (!control.enabled()) { failed=false;lastHealthy=System.nanoTime();return; }
            Instant now=time.now();
            var ids=jdbc.queryForList("SELECT id FROM password_reset_requests WHERE attempts<5 AND expires_at>? AND ((status='PENDING' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_expires_at<=?)) ORDER BY next_attempt_at,id LIMIT 25",UUID.class,stamp(now),stamp(now),stamp(now));
            for (UUID id:ids) { if (stopping) break; process(id); }
            failed=false;lastHealthy=System.nanoTime();
        } catch (RuntimeException failure) {
            failed=true;
            LoggerFactory.getLogger(getClass()).error("Recovery intake worker unavailable category=DATABASE_OR_CAPACITY");
        } finally { workers.release(); }
    }
    private void process(UUID id) {
        UUID owner=UUID.randomUUID();
        Job job=transaction.execute(status->claim(id,owner));
        if (job==null) return;
        String email;
        try { email=crypto.decryptIntake(id,job.payload(),job.nonce(),job.keyId()); }
        catch (RuntimeException failure) {
            resets.finishIntake(id,owner,"FAILED","PAYLOAD_INVALID");
            LoggerFactory.getLogger(getClass()).error("Recovery intake terminal failure job={} category=PAYLOAD_INVALID",id);return;
        }
        try {
            var candidate=users.findByEmail(email);
            if (candidate.isEmpty() || !limits.reserveLinkMail()) { resets.finishIntake(id,owner,"DONE",null);return; }
            resets.issue(candidate.get().getId(),id,owner,email);
        } catch (RuntimeException failure) { retry(job,owner); }
    }
    private Job claim(UUID id,UUID owner) {
        if (!control.enabled()) return null;
        Instant now=time.now();
        int claimed=jdbc.update("UPDATE password_reset_requests SET status='PROCESSING',lease_owner=?,lease_expires_at=?,attempts=attempts+1 WHERE id=? AND attempts<5 AND expires_at>? AND ((status='PENDING' AND next_attempt_at<=?) OR (status='PROCESSING' AND lease_expires_at<=?))",
                owner,stamp(now.plusSeconds(30)),id,stamp(now),stamp(now),stamp(now));
        if (claimed!=1) return null;
        return jdbc.queryForObject("SELECT payload,nonce,key_id,attempts FROM password_reset_requests WHERE id=? AND lease_owner=?",
                (rs,row)->new Job(id,rs.getString(1),rs.getString(2),rs.getString(3),rs.getInt(4)),id,owner);
    }
    private void retry(Job job,UUID owner) {
        if (job.attempts()>=5) {
            resets.finishIntake(job.id(),owner,"FAILED","PROCESSING_EXHAUSTED");
            LoggerFactory.getLogger(getClass()).error("Recovery intake terminal failure job={} category=PROCESSING_EXHAUSTED",job.id());return;
        }
        Instant now=time.now();
        long delay=Math.max(1,Math.round(RETRY[job.attempts()-1]*ThreadLocalRandom.current().nextDouble(.8,1.2)));
        jdbc.update("UPDATE password_reset_requests SET status='PENDING',lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=?,failure_category='DATABASE_TEMPORARY' WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_expires_at>? AND expires_at>?",
                stamp(now.plusSeconds(delay)),job.id(),owner,stamp(now),stamp(now));
    }
    private record Job(UUID id,String payload,String nonce,String keyId,int attempts) {
        @Override public String toString() { return "ResetIntakeJob[redacted]"; }
    }
}
