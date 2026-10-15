package com.campus.tracker.auth.reset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.campus.tracker.auth.reset.PasswordResetService.stamp;
import com.campus.tracker.auth.abuse.AuthThrottleException;

/** Independent bounded phases never hold mail-row locks while acquiring token/account locks. */
@Service
public class ResetMaintenance {
    private final JdbcTemplate jdbc;
    private final DatabaseTime time;
    private final RecoveryCapacity capacity;
    private final TransactionTemplate transaction;
    private final AtomicBoolean running=new AtomicBoolean();
    private volatile long lastCompleted=System.nanoTime();
    private volatile boolean failed;
    public ResetMaintenance(JdbcTemplate jdbc,DatabaseTime time,RecoveryCapacity capacity,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.time=time;this.capacity=capacity;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);
    }
    public void run() {
        if (!running.compareAndSet(false,true)) return;
        try (var database=capacity.database()) {
            Instant now=time.now();
            phase(()->{
                var ids=jdbc.queryForList("SELECT id FROM password_reset_requests WHERE status IN ('PENDING','PROCESSING') AND (expires_at<=? OR (attempts>=5 AND (status='PENDING' OR lease_expires_at<=?))) ORDER BY expires_at,id LIMIT 1000",UUID.class,stamp(now),stamp(now));
                for (UUID id:ids) jdbc.update("UPDATE password_reset_requests SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,completed_at=?,failure_category='DEADLINE_EXPIRED' WHERE id=? AND status IN ('PENDING','PROCESSING') AND (expires_at<=? OR (attempts>=5 AND (status='PENDING' OR lease_expires_at<=?)))",stamp(now),id,stamp(now),stamp(now));
            });
            phase(()->{
                var jobs=jdbc.query("SELECT o.id,o.message_type FROM email_outbox o JOIN password_reset_tokens t ON t.id=o.password_reset_token_id JOIN campus_users u ON u.id=t.user_id WHERE o.message_type IN ('PASSWORD_RESET_LINK','PASSWORD_CHANGED') AND o.status IN ('PENDING','SENDING') AND ((o.message_type='PASSWORD_RESET_LINK' AND (t.expires_at<=? OR t.consumed_at IS NOT NULL OR t.revoked_at IS NOT NULL OR t.credential_version<>u.credential_version OR t.email<>u.email)) OR (o.message_type='PASSWORD_CHANGED' AND (o.created_at<=? OR t.consumed_at IS NULL OR t.email<>u.email))) ORDER BY o.id LIMIT 1000",
                        (rs,row)->new ExpiredMail(rs.getObject(1,UUID.class),rs.getString(2)),stamp(now),stamp(now.minusSeconds(86400)));
                for (var job:jobs) {
                    // A completion occurring after the scan may have cancelled the same job; it remains terminal.
                    boolean notice=job.type().equals("PASSWORD_CHANGED");
                    int changed=jdbc.update("UPDATE email_outbox SET status=?,payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=?,failure_category='DEADLINE_OR_TOKEN_INELIGIBLE' WHERE id=? AND status IN ('PENDING','SENDING')",notice?"FAILED":"CANCELLED",stamp(now),job.id());
                    if (notice && changed>0) LoggerFactory.getLogger(getClass()).error("Recovery notice terminal failure job={} category=DEADLINE_OR_TOKEN_INELIGIBLE",job.id());
                }
            });
            phase(()->{
                var ids=jdbc.queryForList("SELECT id FROM email_outbox WHERE message_type<>'EMAIL_VERIFICATION' AND status IN ('SENT','CANCELLED','FAILED') AND next_attempt_at<? ORDER BY id LIMIT 1000",UUID.class,stamp(now.minusSeconds(7*86400)));
                for (UUID id:ids) jdbc.update("DELETE FROM email_outbox WHERE id=? AND status IN ('SENT','CANCELLED','FAILED') AND next_attempt_at<?",id,stamp(now.minusSeconds(7*86400)));
            });
            phase(()->{
                var ids=jdbc.queryForList("SELECT id FROM password_reset_tokens t WHERE expires_at<? AND NOT EXISTS (SELECT 1 FROM email_outbox o WHERE o.password_reset_token_id=t.id) ORDER BY id LIMIT 1000",UUID.class,stamp(now.minusSeconds(7*86400)));
                for (UUID id:ids) jdbc.update("DELETE FROM password_reset_tokens WHERE id=? AND expires_at<? AND NOT EXISTS(SELECT 1 FROM email_outbox o WHERE o.password_reset_token_id=password_reset_tokens.id)",id,stamp(now.minusSeconds(7*86400)));
            });
            phase(()->{
                var ids=jdbc.queryForList("SELECT id FROM password_reset_requests r WHERE completed_at<? AND status IN ('DONE','CANCELLED','FAILED') AND NOT EXISTS(SELECT 1 FROM password_reset_tokens t WHERE t.request_id=r.id) ORDER BY id LIMIT 1000",UUID.class,stamp(now.minusSeconds(86400)));
                for (UUID id:ids) jdbc.update("DELETE FROM password_reset_requests WHERE id=? AND completed_at<? AND NOT EXISTS(SELECT 1 FROM password_reset_tokens t WHERE t.request_id=password_reset_requests.id)",id,stamp(now.minusSeconds(86400)));
            });
            failed=false;lastCompleted=System.nanoTime();
        } catch (AuthThrottleException saturated) {
            // A saturated pool is retried next poll without an unadmitted pause write.
        } catch (RuntimeException failure) {
            failed=true;
            LoggerFactory.getLogger(getClass()).error("Recovery maintenance failed category=DATABASE_OR_CAPACITY");
            try (var database=capacity.database()) {
                jdbc.update("UPDATE password_reset_control SET health_intake_paused=TRUE WHERE id=1");
            } catch (RuntimeException ignored) { /* DB admission itself fails closed. */ }
        } finally { running.set(false); }
    }
    private void phase(Runnable work) { transaction.executeWithoutResult(status->work.run()); }
    public boolean healthy() { return !failed && System.nanoTime()-lastCompleted<java.util.concurrent.TimeUnit.HOURS.toNanos(24); }
    private record ExpiredMail(UUID id,String type) {}
}
