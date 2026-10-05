package com.campus.tracker.export;

import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.campus.tracker.export.ExportRepository.*;

/** Short database transactions only; CSV and object storage I/O never hold a transaction. */
public class ExportJobRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final boolean postgres;
    private static final String FENCE = "id=? AND state='RUNNING' AND lease_owner=? AND lease_expires_at>? AND expires_at>?";
    public ExportJobRepository(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = jdbc; this.transactions = new TransactionTemplate(manager); this.clock = clock;
        try { postgres = "PostgreSQL".equals(JdbcUtils.extractDatabaseMetaData(jdbc.getDataSource(), java.sql.DatabaseMetaData::getDatabaseProductName)); }
        catch (org.springframework.jdbc.support.MetaDataAccessException failure) { throw new IllegalStateException("Cannot determine export database dialect", failure); }
    }
    public Job claim(UUID lease) {
        Objects.requireNonNull(lease);
        return transactions.execute(status -> {
            var now = at(clock.instant());
            String due = "expires_at>? AND attempts<3 AND ((state='PENDING' AND next_attempt_at<=?) OR (state='RUNNING' AND lease_expires_at<=?))";
            var ids = jdbc.queryForList("SELECT id FROM export_jobs WHERE " + due + " ORDER BY next_attempt_at,created_at,id LIMIT 1" + (postgres ? " FOR UPDATE SKIP LOCKED" : ""), UUID.class, now, now, now);
            if (ids.isEmpty()) return null;
            UUID id = ids.get(0);
            if (jdbc.update("UPDATE export_jobs SET state='RUNNING',lease_owner=?,lease_expires_at=?,attempts=attempts+1 WHERE id=? AND " + due,
                    lease, now.plusMinutes(2), id, now, now, now) != 1) return null;
            return jdbc.queryForObject("SELECT * FROM export_jobs WHERE id=? AND lease_owner=?", ExportRepository::map, id, lease);
        });
    }
    public boolean renew(Job job) {
        var now = at(clock.instant());
        return jdbc.update("UPDATE export_jobs SET lease_expires_at=? WHERE " + FENCE, now.plusMinutes(2), job.id(), job.leaseOwner(), now, now) == 1;
    }
    public boolean beginPublication(Job job, String key) {
        ExportObjectStore.validateKey(key);
        return transactions.execute(status -> {
            if (!renew(job)) return false;
            jdbc.update("INSERT INTO export_objects(object_key,job_id,delete_after) VALUES(?,?,?)", key, job.id(), at(clock.instant().plus(Duration.ofMinutes(10))));
            return true;
        });
    }
    public boolean complete(Job job, String key, long bytes) {
        var now = at(clock.instant());
        return jdbc.update("UPDATE export_jobs SET state='READY',object_key=?,byte_count=?,failure_code=NULL,lease_owner=NULL,lease_expires_at=NULL WHERE " + FENCE,
                key, bytes, job.id(), job.leaseOwner(), now, now) == 1;
    }
    public void fail(Job job, String code, boolean retry) {
        var now = at(clock.instant());
        boolean again = retry && job.attempts() < 3;
        jdbc.update("UPDATE export_jobs SET state=?,failure_code=?,next_attempt_at=?,lease_owner=NULL,lease_expires_at=NULL WHERE " + FENCE,
                again ? "PENDING" : "FAILED", code, now.plusSeconds(30L * job.attempts()), job.id(), job.leaseOwner(), now, now);
    }
    public record Cursor(Instant createdAt, UUID id) {}
    public record Item(UUID id, Instant createdAt, CsvWriter.Row row) {}
    public List<Item> page(Job job, Cursor after) {
        if (job.cutoffId() == null) return List.of();
        var values = new ArrayList<Object>(List.of(job.ownerId(), at(job.cutoffAt()), at(job.cutoffAt()), job.cutoffId()));
        String boundary = "";
        if (after != null) {
            boundary = " AND (created_at>? OR (created_at=? AND id>?))";
            values.add(at(after.createdAt())); values.add(at(after.createdAt())); values.add(after.id());
        }
        var rows = jdbc.query("SELECT id,company,role,location,status,applied_date,deadline,follow_up_date,notes,created_at FROM applications WHERE owner_id=? AND deleted=false AND (created_at<? OR (created_at=? AND id<=?))"
                + boundary + " ORDER BY created_at,id LIMIT 100", (row, index) -> new Item(row.getObject("id", UUID.class), row.getObject("created_at", OffsetDateTime.class).toInstant(),
                new CsvWriter.Row(row.getString("company"), row.getString("role"), row.getString("location"), com.campus.tracker.application.ApplicationStatus.valueOf(row.getString("status")).label(),
                        row.getObject("applied_date", LocalDate.class), row.getObject("deadline", LocalDate.class), row.getObject("follow_up_date", LocalDate.class), List.of(), row.getString("notes"))), values.toArray());
        if (rows.isEmpty()) return rows;
        var stages = new HashMap<UUID, List<CsvWriter.Stage>>();
        String placeholders = String.join(",", Collections.nCopies(rows.size(), "?"));
        var stageRows = jdbc.query("SELECT application_id,name,state,stage_date FROM interview_stages WHERE application_id IN (" + placeholders + ") ORDER BY application_id,position,id LIMIT 3001",
                (row, index) -> Map.entry(row.getObject("application_id", UUID.class), new CsvWriter.Stage(row.getString("name"), com.campus.tracker.application.StageState.valueOf(row.getString("state")).label(), row.getObject("stage_date", LocalDate.class))), rows.stream().map(Item::id).toArray());
        if (stageRows.size() > 3000) throw new IllegalStateException("Export stage bound exceeded");
        stageRows.forEach(stage -> stages.computeIfAbsent(stage.getKey(), ignored -> new ArrayList<>()).add(stage.getValue()));
        return rows.stream().map(item -> {
            var row = item.row();
            return new Item(item.id(), item.createdAt(), new CsvWriter.Row(row.company(), row.role(), row.location(), row.status(), row.appliedDate(), row.deadline(), row.followUpDate(), stages.getOrDefault(item.id(), List.of()), row.notes()));
        }).toList();
    }
    public void expireBatch() {
        var now = at(clock.instant());
        jdbc.update("UPDATE export_jobs SET state='EXPIRED',lease_owner=NULL,lease_expires_at=NULL WHERE id IN (SELECT id FROM export_jobs WHERE expires_at<=? AND state<>'EXPIRED' ORDER BY expires_at,id LIMIT 100)", now);
        jdbc.update("UPDATE export_jobs SET state='FAILED',failure_code='ATTEMPTS_EXHAUSTED',lease_owner=NULL,lease_expires_at=NULL WHERE id IN (SELECT id FROM export_jobs WHERE state='RUNNING' AND attempts>=3 AND lease_expires_at<=? ORDER BY lease_expires_at,id LIMIT 100) AND state='RUNNING' AND lease_expires_at<=?", now, now);
    }
    public List<String> cleanupKeys() {
        return jdbc.queryForList("SELECT o.object_key FROM export_objects o WHERE o.delete_after<=? AND NOT EXISTS (SELECT 1 FROM export_jobs j WHERE j.id=o.job_id AND j.state='READY' AND j.object_key=o.object_key AND j.expires_at>?) ORDER BY o.delete_after,o.object_key LIMIT 100", String.class, at(clock.instant()), at(clock.instant()));
    }
    public void forgetObject(String key) { jdbc.update("DELETE FROM export_objects WHERE object_key=?", key); }
    public void purgeBatch() {
        jdbc.update("DELETE FROM export_jobs WHERE id IN (SELECT j.id FROM export_jobs j WHERE j.expires_at<=? AND NOT EXISTS (SELECT 1 FROM export_objects o WHERE o.job_id=j.id) ORDER BY j.expires_at,j.id LIMIT 100)", at(clock.instant().minus(Duration.ofDays(7))));
    }
}
