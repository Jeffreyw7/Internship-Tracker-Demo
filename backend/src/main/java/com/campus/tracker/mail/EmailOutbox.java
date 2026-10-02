package com.campus.tracker.mail;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.sql.Timestamp;
import java.util.UUID;
@Service
public class EmailOutbox {
    private final JdbcTemplate jdbc;private final OutboxCrypto crypto;private final Clock clock;
    public EmailOutbox(JdbcTemplate jdbc,OutboxCrypto crypto,Clock clock){this.jdbc=jdbc;this.crypto=crypto;this.clock=clock;}
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueueChallenge(UUID challenge,EmailMessage message){enqueue(challenge,null,message);}
    @Transactional(propagation=Propagation.MANDATORY)
    public void enqueueNotification(UUID user,EmailMessage message){enqueue(null,user,message);}
    private void enqueue(UUID challenge,UUID user,EmailMessage message){
        UUID id=UUID.randomUUID();var data=crypto.encrypt(id,message);var now=Timestamp.from(clock.instant());
        jdbc.update("INSERT INTO email_outbox(id,message_type,password_challenge_id,user_id,payload,nonce,key_id,status,next_attempt_at,created_at) VALUES(?,?,?,?,?,?,?,'PENDING',?,?)",
                id,message.type(),challenge,user,data.payload(),data.nonce(),data.keyId(),now,now);
    }
    public void cancelChallenges(UUID user){
        jdbc.update("UPDATE email_outbox SET status='CANCELLED',payload=NULL,nonce=NULL,key_id=NULL,lease_owner=NULL,lease_expires_at=NULL WHERE status IN ('PENDING','SENDING') AND password_challenge_id IN (SELECT id FROM password_challenges WHERE user_id=? AND (revoked_at IS NOT NULL OR consumed_at IS NOT NULL))",user);
    }
}
