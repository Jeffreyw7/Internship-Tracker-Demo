package com.campus.tracker.reminder;

import com.campus.tracker.mail.PermanentDeliveryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

/** Small reconciliation pages and conditional database leases keep network I/O outside transactions. */
@Service
public class ReminderWorker {
    private static final Logger LOG=LoggerFactory.getLogger(ReminderWorker.class);
    private static final long[] RETRY_SECONDS={60,300,900,3600,14400};
    private final ReminderService reminders;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReminderEmailSender sender;
    private final ReminderProperties config;
    private final Clock clock;
    private UUID ownerCursor;
    public ReminderWorker(ReminderService reminders,JdbcTemplate jdbc,PlatformTransactionManager manager,
        ReminderEmailSender sender,ReminderProperties config,Clock clock) {
        this.reminders=reminders;this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.sender=sender;this.config=config;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${campus.reminders.worker-delay:30000}")
    public void scheduled() { if (config.workerEnabled()) runOnce(); }
    public synchronized void runOnce() {
        var owners=reminders.ownersAfter(ownerCursor);
        for (var owner:owners) {
            try { reminders.processOwner(owner); }
            catch (RuntimeException e) { LOG.warn("Reminder reconciliation failed owner={} category={}",owner,e.getClass().getSimpleName()); }
        }
        ownerCursor=owners.size()<100 ? null : owners.get(owners.size()-1);
        if (!config.mailEnabled()) return;
        jdbc.update("""
            UPDATE reminder_deliveries SET status='FAILED',failure_category='DELIVERY_EXHAUSTED',lease_owner=NULL,lease_expires_at=NULL
            WHERE attempts>=6 AND (status='PENDING' OR (status='SENDING' AND lease_expires_at<=?))
            """,now());
        var ids=jdbc.queryForList("""
            SELECT id FROM reminder_deliveries WHERE attempts<6 AND ((status='PENDING' AND next_attempt_at<=?)
            OR (status='SENDING' AND lease_expires_at<=?)) ORDER BY next_attempt_at,id LIMIT 25
            """,UUID.class,now(),now());
        for (var id:ids) deliver(id);
    }
    private void deliver(UUID id) {
        var lease=UUID.randomUUID();
        var job=tx.execute(s->{
            int claimed=jdbc.update("""
                UPDATE reminder_deliveries SET status='SENDING',attempts=attempts+1,lease_owner=?,lease_expires_at=?
                WHERE id=? AND attempts<6 AND ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                """,lease,Timestamp.from(clock.instant().plusSeconds(120)),id,now(),now());
            if (claimed==0) return null;
            return jdbc.queryForObject("SELECT owner_id,delivery_mode,attempts FROM reminder_deliveries WHERE id=?",
                (r,i)->new Job(r.getObject(1,UUID.class),r.getString(2),r.getInt(3)),id);
        });
        if (job==null) return;
        // A job can fall outside the current owner page; recheck date expiry before SMTP.
        reminders.reconcile(job.owner());
        var lines=jdbc.query("""
            SELECT r.company,r.role,r.label,r.event_date FROM reminders r JOIN reminder_preferences p ON p.owner_id=r.owner_id
            WHERE r.delivery_id=? AND r.state='READY' AND r.due_at<=? AND r.email_sent_at IS NULL
            AND p.enabled=TRUE AND p.email_enabled=TRUE ORDER BY r.due_at,r.id
            """,(r,i)->r.getString(1)+" — "+r.getString(2)+"\n"+r.getString(3)+": "+r.getString(4),id,now());
        if (lines.isEmpty()) { finish(id,lease,"CANCELLED",null);return; }
        String recipient=jdbc.queryForObject("SELECT email FROM campus_users WHERE id=?",String.class,job.owner());
        String subject=job.mode().equals("DAILY_DIGEST") ? "Campus daily reminder digest" : "Campus reminder";
        String body=lines.size()+" reminder(s) need your attention:\n\n"+String.join("\n\n",lines)
            +"\n\nOpen your Campus workspace to snooze, dismiss, or review these applications."
            +"\nManage delivery in Reminders. Event dates use your saved application dates.";
        try { sender.send(recipient,subject,body);finish(id,lease,"SENT",null); }
        catch (PermanentDeliveryException e) { finish(id,lease,"FAILED","PROVIDER_REJECTED"); }
        catch (RuntimeException e) {
            if (job.attempts()>=6) { finish(id,lease,"FAILED","DELIVERY_EXHAUSTED");return; }
            jdbc.update("""
                UPDATE reminder_deliveries SET status='PENDING',next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,
                failure_category='PROVIDER_TEMPORARY' WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """,Timestamp.from(clock.instant().plusSeconds(RETRY_SECONDS[job.attempts()-1])),id,lease,now());
            LOG.warn("Reminder email retry job={} attempt={}",id,job.attempts());
        }
    }
    private void finish(UUID id,UUID lease,String status,String category) {
        tx.executeWithoutResult(s->{
            UUID owner=jdbc.queryForObject("SELECT owner_id FROM reminder_deliveries WHERE id=?",UUID.class,id);
            // Match all preference/source actions' owner lock before locking jobs and occurrences.
            jdbc.queryForObject("SELECT owner_id FROM reminder_preferences WHERE owner_id=? FOR UPDATE",UUID.class,owner);
            int finished=jdbc.update("""
                UPDATE reminder_deliveries SET status=?,failure_category=?,sent_at=?,lease_owner=NULL,lease_expires_at=NULL
                WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """,status,category,status.equals("SENT")?now():null,id,lease,now());
            if (finished>0 && status.equals("SENT"))
                jdbc.update("UPDATE reminders SET email_sent_at=? WHERE delivery_id=? AND state='READY' AND email_sent_at IS NULL",now(),id);
        });
    }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private record Job(UUID owner,String mode,int attempts) {}
}
