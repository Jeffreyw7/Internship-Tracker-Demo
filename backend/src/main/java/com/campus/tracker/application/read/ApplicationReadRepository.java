package com.campus.tracker.application.read;

import com.campus.tracker.application.ApplicationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import static com.campus.tracker.application.read.WorkspaceDtos.*;

@Repository
public class ApplicationReadRepository {
    private final JdbcTemplate jdbc;
    public ApplicationReadRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public record ListRow(ApplicationListItem item, String sortValue) {}
    record Predicate(String sql, List<Object> parameters) {}
    static Predicate visible(UUID owner, String q) {
        var parameters = new ArrayList<Object>(); parameters.add(owner);
        String sql = "a.owner_id=? AND a.deleted=false";
        if (!q.isEmpty()) {
            String escaped = "%" + q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            sql += " AND (LOWER(a.company) LIKE ? ESCAPE '\\' OR LOWER(a.role) LIKE ? ESCAPE '\\' OR LOWER(a.location) LIKE ? ESCAPE '\\' OR LOWER(a.notes) LIKE ? ESCAPE '\\'"
                    + " OR EXISTS (SELECT 1 FROM interview_stages search_stage WHERE search_stage.application_id=a.id AND LOWER(search_stage.name) LIKE ? ESCAPE '\\'))";
            for (int i = 0; i < 5; i++) parameters.add(escaped);
        }
        return new Predicate(sql, parameters);
    }
    public List<ListRow> list(UUID owner, ApplicationFilters filters, ApplicationCursorCodec.Boundary boundary) {
        var predicate = visible(owner, filters.q());
        var parameters = predicate.parameters();
        var where = new StringBuilder(predicate.sql());
        if (filters.status() != null) { where.append(" AND a.status=?"); parameters.add(filters.status().name()); }
        switch (filters.tab()) {
            case "active" -> where.append(" AND a.status IN ('APPLIED','INTERVIEWING')");
            case "saved" -> where.append(" AND a.status='SAVED'");
            case "closed" -> where.append(" AND a.status IN ('OFFER','REJECTED','WITHDRAWN')");
        }
        if (filters.dueOnly()) { where.append(" AND a.status NOT IN ('REJECTED','WITHDRAWN') AND a.follow_up_date<=?"); parameters.add(filters.today()); }
        String column = switch (filters.sort()) {
            case "company" -> "LOWER(a.company)";
            case "deadline" -> "a.deadline";
            case "applied" -> "a.applied_date";
            default -> "a.updated_at";
        };
        boolean ascending = filters.sort().equals("company") || filters.sort().equals("deadline");
        boolean nullable = filters.sort().equals("deadline") || filters.sort().equals("applied");
        if (boundary != null) {
            if (boundary.value() == null) {
                if (!nullable) throw new IllegalArgumentException("Invalid null cursor boundary");
                where.append(" AND (").append(column).append(" IS NULL AND a.id<?)"); parameters.add(boundary.id());
            } else {
                Object value = switch (filters.sort()) {
                    case "deadline", "applied" -> ApplicationReadController.parseDate(boundary.value());
                    case "updated" -> parseInstant(boundary.value());
                    default -> boundary.value();
                };
                where.append(" AND ((").append(column).append(ascending ? ">?" : "<?").append(") OR (").append(column).append("=? AND a.id<?)");
                if (nullable) where.append(" OR ").append(column).append(" IS NULL");
                where.append(")"); parameters.add(value); parameters.add(value); parameters.add(boundary.id());
            }
        }
        String order = (nullable ? "CASE WHEN " + column + " IS NULL THEN 1 ELSE 0 END ASC," : "") + column + (ascending ? " ASC" : " DESC") + ",a.id DESC";
        parameters.add(filters.size() + 1);
        return jdbc.query("SELECT a.id,a.company,a.role,a.location,a.status,a.applied_date,a.deadline,a.follow_up_date,a.created_at,a.updated_at,a.version,a.sample," + column
                + " AS sort_value FROM applications a WHERE " + where + " ORDER BY " + order + " LIMIT ?", (row, index) -> {
            var item = new ApplicationListItem(row.getObject("id", UUID.class), row.getString("company"), row.getString("role"), row.getString("location"), ApplicationStatus.valueOf(row.getString("status")),
                    date(row, "applied_date"), date(row, "deadline"), date(row, "follow_up_date"), row.getObject("created_at", OffsetDateTime.class).toInstant(), row.getObject("updated_at", OffsetDateTime.class).toInstant(), row.getLong("version"), row.getBoolean("sample"), null);
            String value = filters.sort().equals("updated") ? row.getObject("sort_value", OffsetDateTime.class).toInstant().toString() : row.getString("sort_value");
            return new ListRow(item, value);
        }, parameters.toArray());
    }
    public Map<UUID, NextActivity> nextActivities(UUID owner, List<UUID> ids) {
        if (ids.isEmpty()) return Map.of();
        if (ids.size() > 100) throw new IllegalArgumentException("Too many preview IDs");
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        var parameters = new ArrayList<Object>(); parameters.add(owner); parameters.addAll(ids);
        String base = "a.owner_id=? AND a.deleted=false AND a.status NOT IN ('REJECTED','WITHDRAWN') AND a.id IN (" + placeholders + ")";
        String sql = activityUnion(base);
        var allParameters = new ArrayList<Object>(); for (int i = 0; i < 3; i++) allParameters.addAll(parameters);
        var result = new HashMap<UUID, NextActivity>();
        jdbc.query("SELECT application_id,id,kind,label,activity_date,stage_id FROM (SELECT feed.*,ROW_NUMBER() OVER (PARTITION BY application_id ORDER BY activity_date,kind,id) AS rank FROM ("
                + sql + ") feed) ranked WHERE rank=1", row -> {
            result.put(row.getObject("application_id", UUID.class), new NextActivity(row.getString("id"), row.getString("kind"), row.getString("label"), row.getObject("activity_date", LocalDate.class), row.getObject("stage_id", UUID.class)));
        }, allParameters.toArray());
        return result;
    }
    static String activityUnion(String predicate) {
        String app = "a.id AS application_id,a.company,a.role,a.location,a.status AS application_status,a.version AS application_version,";
        return "SELECT " + app + "CAST(s.id AS VARCHAR(50)) AS id,'Interview' AS kind,s.name AS label,s.stage_date AS activity_date,s.id AS stage_id FROM applications a JOIN interview_stages s ON s.application_id=a.id WHERE " + predicate + " AND s.state='SCHEDULED' AND s.stage_date IS NOT NULL"
                + " UNION ALL SELECT " + app + "CAST(a.id AS VARCHAR(50)) || '-follow' AS id,'Follow-up' AS kind,'Check in with the team' AS label,a.follow_up_date AS activity_date,CAST(NULL AS UUID) AS stage_id FROM applications a WHERE " + predicate + " AND a.follow_up_date IS NOT NULL"
                + " UNION ALL SELECT " + app + "CAST(a.id AS VARCHAR(50)) || '-deadline' AS id,'Deadline' AS kind,'Submit your application' AS label,a.deadline AS activity_date,CAST(NULL AS UUID) AS stage_id FROM applications a WHERE " + predicate + " AND a.status='SAVED' AND a.deadline IS NOT NULL";
    }
    public List<ActivityItem> activities(UUID owner, String kind, String q, int limit, LocalDate lastDate, String lastKind, String lastId) {
        var predicate = visible(owner, q);
        String base = predicate.sql() + " AND a.status NOT IN ('REJECTED','WITHDRAWN')";
        var parameters = new ArrayList<Object>(); for (int i = 0; i < 3; i++) parameters.addAll(predicate.parameters());
        var where = new StringBuilder("1=1");
        if (kind != null) { where.append(" AND kind=?"); parameters.add(kind); }
        if (lastDate != null) {
            where.append(" AND (activity_date>? OR (activity_date=? AND kind>?) OR (activity_date=? AND kind=? AND id>?))");
            parameters.addAll(List.of(lastDate, lastDate, lastKind, lastDate, lastKind, lastId));
        }
        parameters.add(limit);
        return jdbc.query("SELECT * FROM (" + activityUnion(base) + ") feed WHERE " + where + " ORDER BY activity_date,kind,id LIMIT ?",
                (row, index) -> new ActivityItem(row.getString("id"), row.getObject("application_id", UUID.class), row.getString("company"), row.getString("role"), row.getString("location"),
                        ApplicationStatus.valueOf(row.getString("application_status")), row.getLong("application_version"), row.getString("kind"), row.getString("label"), row.getObject("activity_date", LocalDate.class), row.getObject("stage_id", UUID.class)), parameters.toArray());
    }
    public WorkspaceSummary summary(UUID owner, LocalDate today, Instant readAt) {
        // One SQL statement sees one database snapshot, including the independent stage aggregate.
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                    COALESCE(SUM(CASE WHEN status IN ('APPLIED','INTERVIEWING') THEN 1 ELSE 0 END),0) AS active,
                    COALESCE(SUM(CASE WHEN status='INTERVIEWING' THEN 1 ELSE 0 END),0) AS interviews,
                    COALESCE(SUM(CASE WHEN status='OFFER' THEN 1 ELSE 0 END),0) AS offers,
                    COALESCE(SUM(CASE WHEN status='SAVED' THEN 1 ELSE 0 END),0) AS saved,
                    COALESCE(SUM(CASE WHEN status='APPLIED' THEN 1 ELSE 0 END),0) AS applied,
                    COALESCE(SUM(CASE WHEN status='REJECTED' THEN 1 ELSE 0 END),0) AS rejected,
                    COALESCE(SUM(CASE WHEN status='WITHDRAWN' THEN 1 ELSE 0 END),0) AS withdrawn,
                    COALESCE(SUM(CASE WHEN sample=true THEN 1 ELSE 0 END),0) AS samples,
                    COALESCE(SUM(CASE WHEN status NOT IN ('REJECTED','WITHDRAWN') AND follow_up_date<=? THEN 1 ELSE 0 END),0) AS due,
                    (SELECT COUNT(*) FROM interview_stages s JOIN applications a ON a.id=s.application_id
                        WHERE a.owner_id=? AND a.deleted=false AND a.status NOT IN ('REJECTED','WITHDRAWN')
                            AND s.state='SCHEDULED' AND s.stage_date IS NOT NULL) AS scheduled
                FROM applications WHERE owner_id=? AND deleted=false
                """, (row, index) -> {
            var statuses = new EnumMap<ApplicationStatus, Long>(ApplicationStatus.class);
            statuses.put(ApplicationStatus.SAVED, row.getLong("saved")); statuses.put(ApplicationStatus.APPLIED, row.getLong("applied"));
            statuses.put(ApplicationStatus.INTERVIEWING, row.getLong("interviews")); statuses.put(ApplicationStatus.OFFER, row.getLong("offers"));
            statuses.put(ApplicationStatus.REJECTED, row.getLong("rejected")); statuses.put(ApplicationStatus.WITHDRAWN, row.getLong("withdrawn"));
            return new WorkspaceSummary(row.getLong("total"), row.getLong("active"), row.getLong("interviews"), row.getLong("offers"), row.getLong("due"), row.getLong("scheduled"), row.getLong("samples") > 0, Map.copyOf(statuses), readAt);
        }, today, owner, owner);
    }
    private static OffsetDateTime parseInstant(String value) {
        try { return Instant.parse(value).atOffset(ZoneOffset.UTC); }
        catch (java.time.format.DateTimeParseException failure) { throw new IllegalArgumentException("Invalid timestamp boundary"); }
    }
    private static String date(ResultSet row, String column) throws SQLException {
        var value = row.getObject(column, LocalDate.class); return value == null ? "" : value.toString();
    }
}
