package com.campus.tracker.mail;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class OutboxClaimRepository {
    static final String ELIGIBLE = """
            EXISTS (SELECT 1 FROM email_verification_tokens t JOIN campus_users u ON u.id=t.user_id
                WHERE t.id=email_outbox.verification_token_id AND t.email=u.email
                  AND t.expires_at>? AND t.consumed_at IS NULL AND t.revoked_at IS NULL
                  AND u.email_verified_at IS NULL AND u.email_verification_exempt=false)
            """;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean postgres;

    public OutboxClaimRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.clock = clock;
        try { postgres = "PostgreSQL".equals(JdbcUtils.extractDatabaseMetaData(jdbc.getDataSource(), java.sql.DatabaseMetaData::getDatabaseProductName)); }
        catch (org.springframework.jdbc.support.MetaDataAccessException failure) { throw new IllegalStateException("Cannot determine worker database dialect", failure); }
    }
    public record ClaimedMail(UUID id, String payload, String nonce, String keyId, int attempts, UUID leaseOwner) {
        @Override public String toString() { return "ClaimedMail[id=" + id + "]"; }
    }

    public List<ClaimedMail> claim(int limit, UUID owner) {
        if (limit < 1 || limit > 256 || owner == null) throw new IllegalArgumentException("Invalid mail claim");
        return transactions.execute(status -> {
            var now = now();
            if (postgres) return jdbc.query("""
                    WITH due AS (
                        SELECT id FROM email_outbox WHERE attempts<6 AND
                        ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                        ORDER BY next_attempt_at,id LIMIT ? FOR UPDATE SKIP LOCKED
                    )
                    UPDATE email_outbox SET status='SENDING',lease_owner=?,lease_expires_at=?,attempts=attempts+1
                    WHERE id IN (SELECT id FROM due)
                    RETURNING id,payload,nonce,key_id,attempts
                    """, (row, index) -> new ClaimedMail(row.getObject("id", UUID.class), row.getString("payload"),
                            row.getString("nonce"), row.getString("key_id"), row.getInt("attempts"), owner),
                    now, now, limit, owner, now.plusMinutes(2));
            var ids = jdbc.queryForList("""
                    SELECT id FROM email_outbox WHERE attempts<6 AND
                    ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                    ORDER BY next_attempt_at,id LIMIT ?
                    """, UUID.class, now, now, limit);
            var claimed = new ArrayList<ClaimedMail>();
            for (UUID id : ids) {
                int changed = jdbc.update("""
                        UPDATE email_outbox SET status='SENDING',lease_owner=?,lease_expires_at=?,attempts=attempts+1
                        WHERE id=? AND attempts<6 AND
                        ((status='PENDING' AND next_attempt_at<=?) OR (status='SENDING' AND lease_expires_at<=?))
                        """, owner, now.plusMinutes(2), id, now, now);
                if (changed == 1) claimed.add(jdbc.queryForObject("SELECT id,payload,nonce,key_id,attempts FROM email_outbox WHERE id=? AND lease_owner=?",
                        (row, index) -> new ClaimedMail(row.getObject("id", UUID.class), row.getString("payload"),
                                row.getString("nonce"), row.getString("key_id"), row.getInt("attempts"), owner), id, owner));
            }
            return List.copyOf(claimed);
        });
    }

    public boolean eligible(ClaimedMail job) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE id=? AND status='SENDING' "
                + "AND lease_owner=? AND lease_expires_at>? AND " + ELIGIBLE,
                Integer.class, job.id(), job.leaseOwner(), now(), now()) == 1;
    }
    public boolean finish(ClaimedMail job, String state, String category) {
        if (!List.of("SENT", "FAILED", "CANCELLED").contains(state)) throw new IllegalArgumentException("Invalid mail outcome");
        var now = now();
        return jdbc.update("""
                UPDATE email_outbox SET status=?,payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,
                    failure_category=?,sent_at=?,next_attempt_at=? WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, state, category, "SENT".equals(state) ? now : null, now, job.id(), job.leaseOwner(), now) == 1;
    }
    public boolean retry(ClaimedMail job, OffsetDateTime next) {
        return jdbc.update("""
                UPDATE email_outbox SET status='PENDING',next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL,failure_category='PROVIDER_TEMPORARY'
                WHERE id=? AND status='SENDING' AND lease_owner=? AND lease_expires_at>?
                """, next, job.id(), job.leaseOwner(), now()) == 1;
    }
    private OffsetDateTime now() { return clock.instant().atOffset(ZoneOffset.UTC); }
}
