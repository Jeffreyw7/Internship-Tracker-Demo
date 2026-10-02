package com.campus.tracker.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PasswordChallengeRepository {
    private final JdbcTemplate jdbc;
    public PasswordChallengeRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Challenge(UUID id, UUID userId, String purpose, String email, long version,
            String bindingHash, Instant expiresAt, boolean used) {}
    public void insert(UUID id, CampusUser user, String purpose, String token, String binding, Instant now, Instant expiry) {
        jdbc.update("INSERT INTO password_challenges(id,user_id,purpose,email,token_hash,credential_version,initiator_binding_hash,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, user.getId(), purpose, user.getEmail(), PasswordToken.hash(token), user.getCredentialVersion(),
                binding == null ? null : PasswordToken.hash(binding), Timestamp.from(now), Timestamp.from(expiry));
    }
    public Optional<Challenge> find(String token, boolean lock) {
        return jdbc.query("SELECT * FROM password_challenges WHERE token_hash=?" + (lock ? " FOR UPDATE" : ""),
                (r, i) -> new Challenge(r.getObject("id", UUID.class), r.getObject("user_id", UUID.class),
                        r.getString("purpose"), r.getString("email"), r.getLong("credential_version"),
                        r.getString("initiator_binding_hash"), r.getTimestamp("expires_at").toInstant(),
                        r.getTimestamp("consumed_at") != null || r.getTimestamp("revoked_at") != null),
                PasswordToken.hash(token)).stream().findFirst();
    }
    public void revoke(UUID user, String purpose, Instant now) {
        jdbc.update("UPDATE password_challenges SET revoked_at=? WHERE user_id=? AND consumed_at IS NULL AND revoked_at IS NULL" +
                (purpose == null ? "" : " AND purpose=?"), purpose == null ?
                new Object[]{Timestamp.from(now), user} : new Object[]{Timestamp.from(now), user, purpose});
    }
    public void consume(UUID id, Instant now) {
        jdbc.update("UPDATE password_challenges SET consumed_at=? WHERE id=?", Timestamp.from(now), id);
    }
}
