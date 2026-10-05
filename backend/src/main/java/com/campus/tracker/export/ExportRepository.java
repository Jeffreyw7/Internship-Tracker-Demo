package com.campus.tracker.export;

import com.campus.tracker.common.ApiException;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.campus.tracker.export.ExportDtos.*;

@Repository
public class ExportRepository {
    final JdbcTemplate jdbc;
    public ExportRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record Job(UUID id, UUID ownerId, State state, Instant createdAt, Instant expiresAt,
            Instant cutoffAt, UUID cutoffId, UUID leaseOwner, Instant leaseExpiresAt, int attempts,
            String objectKey, Long byteCount, String failureCode) {
        @Override public String toString() { return "ExportJob[id=" + id + ",state=" + state + "]"; }
    }
    Job create(UUID owner, Instant now, ExportProperties limits) {
        // Serialize admission across API replicas. Application ownership never comes from request JSON.
        jdbc.queryForObject("SELECT id FROM campus_users WHERE id=? FOR UPDATE", UUID.class, owner);
        long active = jdbc.queryForObject("SELECT COUNT(*) FROM export_jobs WHERE owner_id=? AND state IN ('PENDING','RUNNING') AND expires_at>?", Long.class, owner, at(now));
        if (active >= limits.maxActive()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "EXPORT_LIMIT", "Wait for an active export to finish or cancel it.");
        var cutoff = jdbc.query("SELECT id,created_at FROM applications WHERE owner_id=? AND deleted=false AND created_at<=? ORDER BY created_at DESC,id DESC LIMIT 1",
                (row, index) -> new Object[]{row.getObject("id", UUID.class), row.getObject("created_at", OffsetDateTime.class)}, owner, at(now));
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO export_jobs(id,owner_id,state,created_at,expires_at,cutoff_id,cutoff_at) VALUES(?,?,'PENDING',?,?,?,?)",
                id, owner, at(now), at(now.plus(limits.retention())), cutoff.isEmpty() ? null : cutoff.get(0)[0], cutoff.isEmpty() ? null : cutoff.get(0)[1]);
        return owned(owner, id);
    }
    Job owned(UUID owner, UUID id) {
        var jobs = jdbc.query("SELECT id,owner_id,state,created_at,expires_at,cutoff_at,cutoff_id,lease_owner,lease_expires_at,attempts,object_key,byte_count,failure_code FROM export_jobs WHERE id=? AND owner_id=?",
                ExportRepository::map, id, owner);
        if (jobs.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "Export was not found.");
        return jobs.get(0);
    }
    void cancel(UUID owner, UUID id) {
        jdbc.update("UPDATE export_jobs SET state='CANCELLED',lease_owner=NULL,lease_expires_at=NULL WHERE id=? AND owner_id=? AND state IN ('PENDING','RUNNING','READY')", id, owner);
    }
    static OffsetDateTime at(Instant value) { return value.atOffset(ZoneOffset.UTC); }
    static Job map(java.sql.ResultSet row, int index) throws java.sql.SQLException {
        return new Job(row.getObject("id", UUID.class), row.getObject("owner_id", UUID.class), State.valueOf(row.getString("state")),
                instant(row, "created_at"), instant(row, "expires_at"), instant(row, "cutoff_at"), row.getObject("cutoff_id", UUID.class), row.getObject("lease_owner", UUID.class), instant(row, "lease_expires_at"),
                row.getInt("attempts"), row.getString("object_key"), row.getObject("byte_count", Long.class), row.getString("failure_code"));
    }
    private static Instant instant(java.sql.ResultSet row, String column) throws java.sql.SQLException {
        var value = row.getObject(column, OffsetDateTime.class); return value == null ? null : value.toInstant();
    }
}
