package com.campus.tracker.reminder;

import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.sql.*;
import java.time.*;
import java.util.*;
import static com.campus.tracker.reminder.ReminderDtos.*;

/** Owns source reconciliation and reminder state. All owner operations serialize on preferences. */
@Service
@Transactional
public class ReminderService {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ReminderProperties config;
    public ReminderService(JdbcTemplate jdbc, Clock clock, ReminderProperties config) {
        this.jdbc=jdbc; this.clock=clock; this.config=config;
    }
    public Overview overview(UUID owner) {
        ensurePreferences(owner);
        processOwner(owner);
        var p=preferences(owner);
        return new Overview(p,config.mailEnabled(),jdbc.query(VIEW_SQL+" WHERE r.owner_id=? AND r.state IN ('PENDING','READY') ORDER BY r.due_at,r.id LIMIT 200",
            ReminderService::view,owner),jdbc.queryForObject("SELECT COUNT(*) FROM reminders WHERE owner_id=? AND state='READY'",Integer.class,owner));
    }
    public Overview savePreferences(UUID owner, String version, Preferences input) {
        try { ZoneId.of(input.timezone()); }
        catch (DateTimeException e) { throw new ApiException(HttpStatus.BAD_REQUEST,"Choose a valid timezone."); }
        if (input.emailEnabled() && !config.mailEnabled()) throw new ApiException(HttpStatus.BAD_REQUEST,"Email reminders are not configured on this server.");
        ensurePreferences(owner);var current=lock(owner);verifyVersion(current.version(),version);
        boolean compatibleTiming=current.timezone().equals(input.timezone()) && current.deliveryMode()==input.deliveryMode() && current.digestTime().equals(input.digestTime());
        jdbc.update("""
            UPDATE reminder_preferences SET enabled=?,interviews_enabled=?,deadlines_enabled=?,follow_ups_enabled=?,
            interview_lead_days=?,deadline_lead_days=?,follow_up_lead_days=?,timezone=?,reminder_time=?,delivery_mode=?,digest_time=?,
            email_enabled=?,last_digest_date=CASE WHEN ? THEN last_digest_date ELSE NULL END,version=version+1 WHERE owner_id=?
            """,input.enabled(),input.interviewsEnabled(),input.deadlinesEnabled(),input.followUpsEnabled(),input.interviewLeadDays(),
            input.deadlineLeadDays(),input.followUpLeadDays(),input.timezone(),input.reminderTime(),input.deliveryMode().name(),input.digestTime(),input.emailEnabled(),
            compatibleTiming,owner);
        // Preserve eligible retries when category/lead-time edits leave delivery rules compatible.
        if (!compatibleTiming || !input.enabled() || !input.emailEnabled()) {
            jdbc.update("UPDATE reminder_deliveries SET status='CANCELLED',lease_owner=NULL,lease_expires_at=NULL WHERE owner_id=? AND status IN ('PENDING','SENDING')",owner);
            jdbc.update("UPDATE reminders SET delivery_id=NULL WHERE owner_id=? AND email_sent_at IS NULL",owner);
        }
        return overview(owner);
    }
    public ReminderView snooze(UUID owner, UUID id, String version, int minutes) {
        if (minutes<15 || minutes>10080) throw new ApiException(HttpStatus.BAD_REQUEST,"Snooze must be between 15 minutes and seven days.");
        lock(owner);var item=owned(owner,id);verifyVersion(item.version(),version);
        jdbc.update("UPDATE reminders SET state='PENDING',due_at=?,snoozed=TRUE,delivery_id=NULL,email_sent_at=NULL,version=version+1 WHERE id=?",
            Timestamp.from(clock.instant().plusSeconds(minutes*60L)),id);
        return owned(owner,id);
    }
    public ReminderView dismiss(UUID owner, UUID id, String version) {
        lock(owner);var item=owned(owner,id);verifyVersion(item.version(),version);
        jdbc.update("UPDATE reminders SET state='DISMISSED',delivery_id=NULL,version=version+1 WHERE id=?",id);
        return jdbc.queryForObject(VIEW_SQL+" WHERE r.id=? AND r.owner_id=?",ReminderService::view,id,owner);
    }
    /** Called after JPA flush in the same application transaction; does not opt accounts in. */
    public void reconcile(UUID owner) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM reminder_preferences WHERE owner_id=?",Integer.class,owner)==0) return;
        var p=lock(owner);reconcile(owner,p);
    }
    public void processOwner(UUID owner) {
        var p=lock(owner);reconcile(owner,p);
        if (!p.enabled()) return;
        var instant=clock.instant();var local=instant.atZone(ZoneId.of(p.timezone()));
        boolean digest=p.deliveryMode()==DeliveryMode.DAILY_DIGEST;
        var cutoff=digest ? ReminderTiming.due(local.toLocalDate(),0,p.digestTime(),p.timezone()) : instant;
        LocalDate last=jdbc.queryForObject("SELECT last_digest_date FROM reminder_preferences WHERE owner_id=?",(r,i)->r.getObject(1,LocalDate.class),owner);
        if (digest && instant.isBefore(cutoff)) return;
        if (!digest || !local.toLocalDate().equals(last)) {
            jdbc.update("UPDATE reminders SET state='READY',version=version+1 WHERE owner_id=? AND state='PENDING' AND due_at<=?",owner,Timestamp.from(cutoff));
            List<UUID> ids=p.emailEnabled() && config.mailEnabled()
                ? jdbc.queryForList("SELECT id FROM reminders WHERE owner_id=? AND state='READY' AND due_at<=? AND email_sent_at IS NULL AND delivery_id IS NULL ORDER BY due_at,id"+(digest?"":" LIMIT 100"),UUID.class,owner,Timestamp.from(cutoff))
                : List.of();
            // Empty windows also close the digest day; ordinary late arrivals wait for tomorrow.
            if (digest) jdbc.update("UPDATE reminder_preferences SET last_digest_date=? WHERE owner_id=?",local.toLocalDate(),owner);
            if (digest) for (int start=0;start<ids.size();start+=100) assign(owner,ids.subList(start,Math.min(start+100,ids.size())),p.deliveryMode());
            else for (var id:ids) assign(owner,List.of(id),p.deliveryMode());
        }
        if (digest) {
            // These sources expire at midnight, so a later digest would never deliver them.
            jdbc.update("""
                UPDATE reminders SET state='READY',version=version+1 WHERE owner_id=? AND state='PENDING'
                AND kind IN ('INTERVIEW','DEADLINE') AND event_date=? AND due_at<=?
                """,owner,local.toLocalDate(),Timestamp.from(instant));
            if (p.emailEnabled() && config.mailEnabled()) {
                var ids=jdbc.queryForList("""
                    SELECT id FROM reminders WHERE owner_id=? AND state='READY' AND kind IN ('INTERVIEW','DEADLINE')
                    AND event_date=? AND due_at<=? AND email_sent_at IS NULL AND delivery_id IS NULL ORDER BY due_at,id LIMIT 100
                    """,UUID.class,owner,local.toLocalDate(),Timestamp.from(instant));
                for (var id:ids) assign(owner,List.of(id),DeliveryMode.IMMEDIATE);
            }
        }
    }
    @Transactional(readOnly=true)
    public List<UUID> ownersAfter(UUID after) {
        return after==null
            ? jdbc.queryForList("SELECT owner_id FROM reminder_preferences WHERE enabled=TRUE ORDER BY owner_id LIMIT 100",UUID.class)
            : jdbc.queryForList("SELECT owner_id FROM reminder_preferences WHERE enabled=TRUE AND owner_id>? ORDER BY owner_id LIMIT 100",UUID.class,after);
    }
    private void assign(UUID owner, List<UUID> ids, DeliveryMode mode) {
        var job=UUID.randomUUID();
        jdbc.update("INSERT INTO reminder_deliveries(id,owner_id,delivery_mode,next_attempt_at,created_at) VALUES(?,?,?,?,?)",job,owner,mode.name(),now(),now());
        for (var id:ids) jdbc.update("UPDATE reminders SET delivery_id=? WHERE id=?",job,id);
    }
    private void ensurePreferences(UUID owner) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM reminder_preferences WHERE owner_id=?",Integer.class,owner)>0) return;
        if (jdbc.queryForObject("SELECT COUNT(*) FROM campus_users WHERE id=?",Integer.class,owner)==0)
            throw new ApiException(HttpStatus.NOT_FOUND,"Reminder not found.");
        // Atomic initialization avoids a user-row lock conflicting with workers' foreign-key checks.
        jdbc.update("INSERT INTO reminder_preferences(owner_id) VALUES(?) ON CONFLICT DO NOTHING",owner);
    }
    private PreferenceView lock(UUID owner) {
        var rows=jdbc.query("SELECT * FROM reminder_preferences WHERE owner_id=? FOR UPDATE",ReminderService::preference,owner);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Reminder not found.");
        return rows.get(0);
    }
    private PreferenceView preferences(UUID owner) {
        return jdbc.queryForObject("SELECT * FROM reminder_preferences WHERE owner_id=?",ReminderService::preference,owner);
    }
    private void reconcile(UUID owner, PreferenceView p) {
        var today=clock.instant().atZone(ZoneId.of(p.timezone())).toLocalDate();
        var sources=new ArrayList<Source>();
        if (p.enabled()) {
            if (p.interviewsEnabled()) sources.addAll(jdbc.query("""
                SELECT a.id application_id,s.id source_id,a.company,a.role,s.name label,s.stage_date event_date
                FROM applications a JOIN interview_stages s ON s.application_id=a.id
                WHERE a.owner_id=? AND a.deleted=FALSE AND a.sample=FALSE AND a.status IN ('APPLIED','INTERVIEWING')
                AND s.state='SCHEDULED' AND s.stage_date>=?
                """,(r,i)->source(r,"INTERVIEW",p.interviewLeadDays()),owner,today));
            if (p.deadlinesEnabled()) sources.addAll(jdbc.query("""
                SELECT id application_id,id source_id,company,role,'Application deadline' label,deadline event_date
                FROM applications WHERE owner_id=? AND deleted=FALSE AND sample=FALSE AND status='SAVED' AND deadline>=?
                """,(r,i)->source(r,"DEADLINE",p.deadlineLeadDays()),owner,today));
            if (p.followUpsEnabled()) sources.addAll(jdbc.query("""
                SELECT id application_id,id source_id,company,role,'Follow up' label,follow_up_date event_date
                FROM applications WHERE owner_id=? AND deleted=FALSE AND sample=FALSE AND status IN ('APPLIED','INTERVIEWING') AND follow_up_date IS NOT NULL
                """,(r,i)->source(r,"FOLLOW_UP",p.followUpLeadDays()),owner));
        }
        var existing=jdbc.query("SELECT * FROM reminders WHERE owner_id=?",(r,i)->new Existing(r.getObject("id",UUID.class),
            new Key(r.getString("kind"),r.getObject("source_id",UUID.class),r.getObject("event_date",LocalDate.class)),
            r.getString("state"),r.getBoolean("snoozed"),r.getTimestamp("due_at").toInstant(),r.getString("company"),r.getString("role"),r.getString("label")),owner);
        var byKey=new HashMap<Key,Existing>();existing.forEach(e->byKey.put(e.key(),e));
        var active=new HashSet<Key>();
        for (var source:sources) {
            var key=new Key(source.kind(),source.sourceId(),source.date());active.add(key);
            var due=ReminderTiming.due(source.date(),source.lead(),p.reminderTime(),p.timezone());var old=byKey.get(key);
            if (old==null) jdbc.update("""
                INSERT INTO reminders(id,owner_id,application_id,source_id,kind,event_date,company,role,label,due_at) VALUES(?,?,?,?,?,?,?,?,?,?)
                """,UUID.randomUUID(),owner,source.applicationId(),source.sourceId(),source.kind(),source.date(),source.company(),source.role(),source.label(),Timestamp.from(due));
            else if (!old.state().equals("DISMISSED")) {
                boolean reactivated=old.state().equals("CANCELLED");
                var nextDue=old.snoozed() || old.state().equals("READY") ? old.due() : due;
                if (reactivated || !nextDue.equals(old.due()) || !source.company().equals(old.company()) || !source.role().equals(old.role()) || !source.label().equals(old.label()))
                    jdbc.update("UPDATE reminders SET state=?,due_at=?,company=?,role=?,label=?,version=version+1 WHERE id=?",
                        reactivated?"PENDING":old.state(),Timestamp.from(nextDue),source.company(),source.role(),source.label(),old.id());
            }
        }
        for (var old:existing) if (!active.contains(old.key()) && (old.state().equals("PENDING") || old.state().equals("READY")))
            jdbc.update("UPDATE reminders SET state='CANCELLED',delivery_id=NULL,version=version+1 WHERE id=?",old.id());
    }
    private ReminderView owned(UUID owner,UUID id) {
        var rows=jdbc.query(VIEW_SQL+" WHERE r.id=? AND r.owner_id=? AND r.state IN ('PENDING','READY')",ReminderService::view,id,owner);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Reminder not found.");return rows.get(0);
    }
    private static void verifyVersion(long actual,String header) {
        if (header==null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,"If-Match is required for changes.");
        String value=header.trim();if (value.matches("\"[0-9]+\"")) value=value.substring(1,value.length()-1);
        if (!value.matches("[0-9]+")) throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid If-Match version.");
        long expected;try { expected=Long.parseLong(value); } catch (NumberFormatException e) { throw new ApiException(HttpStatus.BAD_REQUEST,"Invalid If-Match version."); }
        if (expected!=actual) throw new ApiException(HttpStatus.CONFLICT,"These reminders changed elsewhere. Refresh before trying again.");
    }
    private Timestamp now() { return Timestamp.from(clock.instant()); }
    private static PreferenceView preference(ResultSet r,int i) throws SQLException {
        return new PreferenceView(r.getBoolean("enabled"),r.getBoolean("interviews_enabled"),r.getBoolean("deadlines_enabled"),r.getBoolean("follow_ups_enabled"),
            r.getInt("interview_lead_days"),r.getInt("deadline_lead_days"),r.getInt("follow_up_lead_days"),r.getString("timezone"),r.getString("reminder_time"),
            DeliveryMode.valueOf(r.getString("delivery_mode")),r.getString("digest_time"),r.getBoolean("email_enabled"),r.getLong("version"));
    }
    private static final String VIEW_SQL="""
        SELECT r.*,CASE WHEN r.email_sent_at IS NOT NULL THEN 'SENT' WHEN d.status IS NULL THEN 'NONE' ELSE d.status END email_status
        FROM reminders r LEFT JOIN reminder_deliveries d ON r.delivery_id=d.id
        """;
    private static ReminderView view(ResultSet r,int i) throws SQLException {
        return new ReminderView(r.getObject("id",UUID.class),r.getObject("application_id",UUID.class),r.getString("kind"),r.getString("company"),r.getString("role"),
            r.getString("label"),r.getObject("event_date",LocalDate.class).toString(),r.getTimestamp("due_at").toInstant(),r.getString("state"),r.getBoolean("snoozed"),r.getLong("version"),r.getString("email_status"));
    }
    private static Source source(ResultSet r,String kind,int lead) throws SQLException {
        return new Source(r.getObject("application_id",UUID.class),r.getObject("source_id",UUID.class),kind,r.getObject("event_date",LocalDate.class),r.getString("company"),r.getString("role"),r.getString("label"),lead);
    }
    private record Key(String kind,UUID source,LocalDate date) {}
    private record Source(UUID applicationId,UUID sourceId,String kind,LocalDate date,String company,String role,String label,int lead) {}
    private record Existing(UUID id,Key key,String state,boolean snoozed,Instant due,String company,String role,String label) {}
}
