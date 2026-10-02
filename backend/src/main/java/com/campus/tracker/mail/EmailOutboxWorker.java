package com.campus.tracker.mail;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Short database leases surround SMTP; network calls never hold database transactions. */
@Service
public class EmailOutboxWorker {
    private static final Logger LOG=LoggerFactory.getLogger(EmailOutboxWorker.class);
    private static final long[] DELAYS={60,300,900,3600,14400};
    private static final String ELIGIBLE="""
        (message_type='CHANGED' OR EXISTS(SELECT 1 FROM password_challenges c JOIN campus_users u ON u.id=c.user_id
        WHERE c.id=email_outbox.password_challenge_id AND c.purpose=email_outbox.message_type
        AND c.email=u.email AND c.credential_version=u.credential_version AND c.expires_at>?
        AND c.consumed_at IS NULL AND c.revoked_at IS NULL))
        """;
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final OutboxCrypto crypto;
    private final EmailSender sender;private final MailProperties config;private final Clock clock;
    public EmailOutboxWorker(JdbcTemplate jdbc,PlatformTransactionManager manager,OutboxCrypto crypto,
            EmailSender sender,MailProperties config,Clock clock){
        this.jdbc=jdbc;this.tx=new TransactionTemplate(manager);this.crypto=crypto;this.sender=sender;this.config=config;this.clock=clock;
    }
    @Scheduled(fixedDelayString="${campus.password.worker-delay:5000}")
    public void scheduled(){if(config.isEnabled()&&config.isWorkerEnabled()) deliverPending();}
    public void deliverPending(){
        cleanup();var now=now();
        var ids=jdbc.queryForList("SELECT id FROM email_outbox WHERE attempts<6 AND ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?)) ORDER BY next_attempt_at,id LIMIT 25",UUID.class,now,now);
        for(UUID id:ids){
            UUID owner=UUID.randomUUID();
            Job job=tx.execute(s->{
                int count=jdbc.update("UPDATE email_outbox SET status='SENDING',attempts=attempts+1,lease_owner=?,lease_expires_at=? WHERE id=? AND attempts<6 AND ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))",
                        owner,Timestamp.from(clock.instant().plusSeconds(120)),id,now(),now());
                if(count==0)return null;
                return jdbc.queryForObject("SELECT payload,nonce,key_id,attempts FROM email_outbox WHERE id=?",(r,i)->new Job(r.getString(1),r.getString(2),r.getString(3),r.getInt(4)),id);
            });
            if(job==null)continue;
            if(jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE id=? AND lease_owner=? AND "+ELIGIBLE,Integer.class,id,owner,now())==0){finish(id,owner,"CANCELLED",null);continue;}
            EmailMessage message;
            try{message=crypto.decrypt(id,job.payload(),job.nonce(),job.keyId());}
            catch(RuntimeException e){finish(id,owner,"FAILED","PAYLOAD_INVALID");continue;}
            try{sender.send(message);finish(id,owner,"SENT",null);}
            catch(PermanentDeliveryException e){finish(id,owner,"FAILED","PROVIDER_REJECTED");}
            catch(RuntimeException e){
                if(job.attempts()>=6){finish(id,owner,"FAILED","DELIVERY_EXHAUSTED");continue;}
                long delay=Math.round(DELAYS[job.attempts()-1]*ThreadLocalRandom.current().nextDouble(0.8,1.2));
                jdbc.update("UPDATE email_outbox SET status='PENDING',next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,failure_category='PROVIDER_TEMPORARY' WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?",
                        Timestamp.from(clock.instant().plusSeconds(delay)),id,owner,now());
                LOG.warn("Password email retry job={} attempt={}",id,job.attempts());
            }
        }
        Integer delayed=jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE status IN ('PENDING','SENDING') AND created_at<?",Integer.class,Timestamp.from(clock.instant().minusSeconds(300)));
        if(delayed!=null&&delayed>0)LOG.warn("Password mail delayed jobs={} thresholdMinutes=5",delayed);
    }
    private void finish(UUID id,UUID owner,String status,String category){
        jdbc.update("UPDATE email_outbox SET status=?,failure_category=?,sent_at=?,payload=NULL,nonce=NULL,key_id=NULL,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=? WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?",
                status,category,status.equals("SENT")?now():null,now(),id,owner,now());
    }
    private void cleanup(){tx.executeWithoutResult(s->{
        jdbc.update("UPDATE email_outbox SET status='CANCELLED',payload=NULL,nonce=NULL,key_id=NULL,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=? WHERE status IN ('PENDING','SENDING') AND NOT "+ELIGIBLE,now(),now());
        jdbc.update("UPDATE email_outbox SET status='FAILED',payload=NULL,nonce=NULL,key_id=NULL,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=?,failure_category='DELIVERY_EXHAUSTED' WHERE attempts>=6 AND (status='PENDING' OR (status='SENDING' AND lease_expires_at<=?))",now(),now());
        Timestamp cutoff=Timestamp.from(clock.instant().minusSeconds(7*86400));
        jdbc.update("DELETE FROM email_outbox WHERE status IN ('SENT','CANCELLED','FAILED') AND next_attempt_at<?",cutoff);
        jdbc.update("DELETE FROM password_challenges WHERE expires_at<? AND NOT EXISTS(SELECT 1 FROM email_outbox o WHERE o.password_challenge_id=password_challenges.id)",cutoff);
        jdbc.update("DELETE FROM auth_rate_limits WHERE expires_at<?",now());
    });}
    private Timestamp now(){return Timestamp.from(clock.instant());}
    private record Job(String payload,String nonce,String keyId,int attempts){@Override public String toString(){return "Job[REDACTED]";}}
}
