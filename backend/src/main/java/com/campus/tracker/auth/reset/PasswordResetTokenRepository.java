package com.campus.tracker.auth.reset;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.*;
import java.time.Instant;
import java.util.*;

@Repository
public class PasswordResetTokenRepository {
    private final JdbcTemplate jdbc;
    public PasswordResetTokenRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public Optional<Challenge> find(String digest,boolean lock) {
        return jdbc.query("SELECT id,user_id,email,credential_version,expires_at,consumed_at,revoked_at FROM password_reset_tokens WHERE token_hash=?"
                +(lock?" FOR UPDATE":""),(rs,row)->new Challenge(rs.getObject("id",UUID.class),rs.getObject("user_id",UUID.class),
                rs.getString("email"),rs.getLong("credential_version"),rs.getTimestamp("expires_at").toInstant(),
                instant(rs,"consumed_at"),instant(rs,"revoked_at")),digest).stream().findFirst();
    }
    private static Instant instant(ResultSet rs,String column) throws SQLException {
        Timestamp value=rs.getTimestamp(column); return value==null?null:value.toInstant();
    }
    public record Challenge(UUID id,UUID userId,String email,long version,Instant expiresAt,Instant consumedAt,Instant revokedAt) {
        public boolean active(Instant now) { return consumedAt==null && revokedAt==null && expiresAt.isAfter(now); }
        @Override public String toString() { return "ResetChallenge[redacted]"; }
    }
}
