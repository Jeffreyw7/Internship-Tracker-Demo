package com.campus.tracker.auth.reset;

import com.campus.tracker.auth.*;
import com.campus.tracker.auth.abuse.*;
import com.campus.tracker.auth.verification.AuthRateLimiter;
import com.campus.tracker.mail.*;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;

/** Explicit short transactions keep database connections outside BCrypt work. */
@Service
public class PasswordResetService {
    public record ConfirmationResult(UUID userId, AuthDtos.Accepted response) {}

    private final JdbcTemplate jdbc;
    private final UserRepository users;
    private final PasswordResetTokenRepository tokens;
    private final PasswordEncoder passwords;
    private final AuthWorkLimiter work;
    private final AuthRateLimiter limits;
    private final EmailOutbox outbox;
    private final OutboxCrypto crypto;
    private final DatabaseTime time;
    private final ResetControl control;
    private final RecoveryCapacity capacity;
    private final ResetAudit audit;
    private final TransactionTemplate transaction;

    public PasswordResetService(JdbcTemplate jdbc,UserRepository users,PasswordResetTokenRepository tokens,
            PasswordEncoder passwords,AuthWorkLimiter work,AuthRateLimiter limits,EmailOutbox outbox,OutboxCrypto crypto,
            DatabaseTime time,ResetControl control,RecoveryCapacity capacity,ResetAudit audit,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.users=users;this.tokens=tokens;this.passwords=passwords;this.work=work;this.limits=limits;
        this.outbox=outbox;this.crypto=crypto;this.time=time;this.control=control;this.capacity=capacity;this.audit=audit;
        transaction=new TransactionTemplate(manager);transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);transaction.setTimeout(5);
    }

    public AuthDtos.Accepted request(String submittedEmail,String ip) {
        // No campus_users read is allowed on this acceptance path, even for unknown addresses.
        try (var ingress=capacity.ingress();var database=capacity.database()) {
            control.requireEnabled(true);
            limits.requireResetRequestAllowed(ip);
            String email=submittedEmail.trim().toLowerCase(Locale.ROOT);
            if (!limits.reserveResetAddress(email)) return accepted();
            UUID id=UUID.randomUUID();
            var encrypted=crypto.encryptIntake(id,email);
            transaction.executeWithoutResult(status -> {
                Instant now=time.now();long order=time.nextOrder();
                jdbc.update("INSERT INTO password_reset_requests(id,request_order,payload,key_id,nonce,status,attempts,next_attempt_at,created_at,expires_at) VALUES(?,?,?,?,?,'PENDING',0,?,?,?)",
                        id,order,encrypted.payload(),encrypted.keyId(),encrypted.nonce(),stamp(now),stamp(now),stamp(now.plusSeconds(600)));
            });
            return accepted();
        } catch (DataAccessException | org.springframework.transaction.TransactionException | IllegalStateException failure) { throw ResetControl.unavailable(); }
    }

    public ConfirmationResult confirm(String token,String newPassword,String ip) {
        try {
            try (var database=capacity.database()) {
                control.requireEnabled(false);
                limits.requireResetConfirmationAllowed(token==null?"missing":token,ip);
            }
            if (!ResetTokens.canonical(token)) throw ResetTokens.invalid();
            PasswordPolicy.validate(newPassword);
            String digest=ResetTokens.digest(token);
            UUID resetUserId;
            var permit=work.tryAcquire();
            if (permit==null) throw new AuthThrottleException(1);
            try (permit) {
                PasswordResetTokenRepository.Challenge first;
                try (var database=capacity.database()) {
                    first=tokens.find(digest,false).orElseThrow(ResetTokens::invalid);
                    if (!first.active(time.now())) throw ResetTokens.invalid();
                    resetUserId=first.userId();
                }
                String hash=passwords.encode(newPassword);
                try (var database=capacity.database()) {
                    transaction.executeWithoutResult(status -> complete(first,digest,hash));
                }
            }
            return new ConfirmationResult(resetUserId,
                    new AuthDtos.Accepted("PASSWORD_RESET_COMPLETE","Your password has been reset. Sign in with your new password."));
        } catch (DataAccessException | org.springframework.transaction.TransactionException | IllegalStateException failure) { throw ResetControl.unavailable(); }
    }

    private void complete(PasswordResetTokenRepository.Challenge first,String digest,String hash) {
        CampusUser user=users.findByIdForUpdate(first.userId()).orElseThrow(ResetTokens::invalid);
        var challenge=tokens.find(digest,true).orElseThrow(ResetTokens::invalid);
        Instant now=time.now();
        if (!challenge.active(now) || !challenge.userId().equals(user.getId()) || !challenge.email().equals(user.getEmail())
                || challenge.version()!=user.getCredentialVersion()) throw ResetTokens.invalid();
        long cutoff=time.nextOrder();
        // The primary clock is evaluated in the consumption statement, not at transaction start.
        String expiry=time.isPostgres()?"clock_timestamp()":"?";
        String consume="UPDATE password_reset_tokens SET consumed_at=? WHERE id=? AND consumed_at IS NULL AND revoked_at IS NULL AND expires_at>"+expiry;
        int consumed=time.isPostgres()?jdbc.update(consume,stamp(now),challenge.id()):jdbc.update(consume,stamp(now),challenge.id(),stamp(time.now()));
        if (consumed!=1) throw ResetTokens.invalid();
        user.replacePassword(hash,cutoff);users.saveAndFlush(user);
        jdbc.update("UPDATE password_reset_tokens SET revoked_at=? WHERE user_id=? AND id<>? AND consumed_at IS NULL AND revoked_at IS NULL",stamp(now),user.getId(),challenge.id());
        // Stable mail-row ordering avoids competing multi-row cancellation lock order.
        var mailIds=jdbc.queryForList("SELECT id FROM email_outbox WHERE message_type='PASSWORD_RESET_LINK' AND password_reset_token_id IN (SELECT id FROM password_reset_tokens WHERE user_id=?) AND status IN ('PENDING','SENDING') ORDER BY id",UUID.class,user.getId());
        for (UUID id:mailIds) jdbc.update("UPDATE email_outbox SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=? WHERE id=? AND status IN ('PENDING','SENDING')",stamp(now),id);
        outbox.enqueuePasswordChanged(challenge.id(),user.getEmail(),now);
        jdbc.update("INSERT INTO auth_security_events(id,completion_id,event_type,occurred_at,account_subject,correlation_id) VALUES(?,?,'PASSWORD_RESET_COMPLETED',?,?,?)",
                UUID.randomUUID(),challenge.id(),stamp(now),audit.subject(user.getId()),UUID.randomUUID());
    }

    void issue(UUID candidate,UUID intakeId,UUID owner,String email) {
        transaction.executeWithoutResult(status -> {
            CampusUser user=users.findByIdForUpdate(candidate).orElse(null);
            var requests=jdbc.query("SELECT request_order,expires_at FROM password_reset_requests WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_expires_at>? FOR UPDATE",
                    (rs,row)->new IntakeDeadline(rs.getLong(1),rs.getTimestamp(2).toInstant()),intakeId,owner,stamp(time.now()));
            if (requests.isEmpty()) return;
            var request=requests.get(0);Instant now=time.now();
            long order=request.order();Instant deadline=request.expiresAt();
            if (user==null || !user.getEmail().equals(email) || order<=user.getResetRequestCutoff() || !deadline.isAfter(now)) {
                finishIntake(intakeId,owner,"DONE",null);return;
            }
            UUID tokenId=UUID.randomUUID();String token=ResetTokens.create();
            jdbc.update("INSERT INTO password_reset_tokens(id,request_id,user_id,email,token_hash,credential_version,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?)",
                    tokenId,intakeId,user.getId(),user.getEmail(),ResetTokens.digest(token),user.getCredentialVersion(),stamp(now),stamp(now.plusSeconds(1800)));
            outbox.enqueueReset(tokenId,email,token);
            int finalized=jdbc.update("UPDATE password_reset_requests SET status='DONE',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,completed_at=? WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_expires_at>? AND expires_at>?",
                    stamp(time.now()),intakeId,owner,stamp(time.now()),stamp(time.now()));
            if (finalized!=1) throw new IllegalStateException("Recovery lease or deadline was lost");
        });
    }
    boolean finishIntake(UUID id,UUID owner,String state,String category) {
        Instant now=time.now();
        return jdbc.update("UPDATE password_reset_requests SET status=?,payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,completed_at=?,failure_category=? WHERE id=? AND status='PROCESSING' AND lease_owner=? AND lease_expires_at>?",
                state,stamp(now),category,id,owner,stamp(now))==1;
    }
    static Timestamp stamp(Instant instant) { return Timestamp.from(instant); }
    private record IntakeDeadline(long order,Instant expiresAt) {}
    private AuthDtos.Accepted accepted() {
        return new AuthDtos.Accepted("PASSWORD_RESET_REQUEST_ACCEPTED","If recovery is available for this address, check your inbox for a reset link.");
    }
}
