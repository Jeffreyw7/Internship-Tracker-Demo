package com.campus.tracker.mail;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Separate bounded maintenance schedule: a delivery poll never sweeps the account tables. */
@Service
public class OutboxMaintenance {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public OutboxMaintenance(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.clock = clock;
    }
    public int runBatch(int maximum) {
        if (maximum < 1 || maximum > 1000) throw new IllegalArgumentException("Invalid maintenance batch");
        return transactions.execute(status -> {
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            var cutoff = now.minusDays(7);
            int changed = 0;
            var ineligible = jdbc.queryForList("SELECT id FROM email_outbox WHERE status IN ('PENDING','SENDING') AND NOT "
                    + OutboxClaimRepository.ELIGIBLE + " ORDER BY next_attempt_at,id LIMIT ?", UUID.class, now, maximum);
            for (UUID id : ineligible) changed += jdbc.update("""
                    UPDATE email_outbox SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,
                        lease_expires_at=NULL,next_attempt_at=?,failure_category='TOKEN_INELIGIBLE'
                    WHERE id=? AND status IN ('PENDING','SENDING') AND NOT
                    """ + OutboxClaimRepository.ELIGIBLE, now, id, now);
            if (changed >= maximum) return changed;
            var exhausted = jdbc.queryForList("""
                    SELECT id FROM email_outbox WHERE attempts>=6 AND
                    ((status='SENDING' AND lease_expires_at<=?) OR status='PENDING') ORDER BY next_attempt_at,id LIMIT ?
                    """, UUID.class, now, maximum - changed);
            for (UUID id : exhausted) changed += jdbc.update("""
                    UPDATE email_outbox SET status='FAILED',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,
                        lease_expires_at=NULL,next_attempt_at=?,failure_category='DELIVERY_EXHAUSTED'
                    WHERE id=? AND attempts>=6 AND ((status='SENDING' AND lease_expires_at<=?) OR status='PENDING')
                    """, now, id, now);
            if (changed >= maximum) return changed;
            var terminal = jdbc.queryForList("SELECT id FROM email_outbox WHERE status IN ('SENT','CANCELLED','FAILED') "
                    + "AND next_attempt_at<? ORDER BY next_attempt_at,id LIMIT ?", UUID.class, cutoff, maximum - changed);
            for (UUID id : terminal) changed += jdbc.update("DELETE FROM email_outbox WHERE id=? AND status IN ('SENT','CANCELLED','FAILED') AND next_attempt_at<?", id, cutoff);
            if (changed >= maximum) return changed;
            var tokens = jdbc.queryForList("SELECT id FROM email_verification_tokens WHERE expires_at<? AND "
                    + "NOT EXISTS (SELECT 1 FROM email_outbox WHERE verification_token_id=email_verification_tokens.id) ORDER BY expires_at,id LIMIT ?",
                    UUID.class, cutoff, maximum - changed);
            for (UUID id : tokens) changed += jdbc.update("DELETE FROM email_verification_tokens WHERE id=? AND expires_at<? AND "
                    + "NOT EXISTS (SELECT 1 FROM email_outbox WHERE verification_token_id=email_verification_tokens.id)", id, cutoff);
            if (changed >= maximum) return changed;
            var limits = jdbc.queryForList("SELECT scope,subject_hash,window_start FROM auth_rate_limits WHERE expires_at<? ORDER BY expires_at LIMIT ?", now, maximum - changed);
            for (var limit : limits) changed += jdbc.update("DELETE FROM auth_rate_limits WHERE scope=? AND subject_hash=? AND window_start=? AND expires_at<?",
                    limit.get("scope"), limit.get("subject_hash"), limit.get("window_start"), now);
            return changed;
        });
    }
}
