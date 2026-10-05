package com.campus.tracker;

import com.campus.tracker.application.read.*;
import com.campus.tracker.migration.MigrationMain;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static com.campus.tracker.application.read.WorkspaceDtos.*;
import static org.assertj.core.api.Assertions.*;

class WorkspaceReadIT {
    @Test void postgresReadParityAndSkewedHistoryQueryPlans() throws Exception {
        try (var database = new PostgresPrivilegesIT.TestDatabase(false)) {
            var provision = database.psql("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", database.getUsername(), "-d", "campus", "-f", "provision.sql");
            assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
            MigrationMain.run(Map.of("MIGRATION_DATABASE_URL", database.getJdbcUrl(), "MIGRATION_DATABASE_USERNAME", "campus_migrator", "MIGRATION_DATABASE_PASSWORD", "migration-'quote-password"));
            var admin = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
            UUID owner = UUID.randomUUID(), other = UUID.randomUUID();
            var now = OffsetDateTime.parse("2026-10-04T10:00:00Z");
            for (UUID account : List.of(owner, other)) admin.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", account, account + "@example.test", "Test", "synthetic", now);
            // A large history plus a substantially larger unrelated account exposes accidental global work.
            for (var account : List.of(owner, other)) admin.update("""
                    INSERT INTO applications(id,owner_id,company,role,location,status,notes,created_at,updated_at)
                    SELECT md5(? || CAST(n AS text))::uuid,?,'Skew ' || n,'Intern','Remote','APPLIED','',?,?
                    FROM generate_series(1,?) n
                    """, account.toString(), account, now, now, account.equals(owner) ? 5000 : 20000);
            var ids = new ArrayList<UUID>();
            for (int i = 0; i < 3; i++) {
                var id = UUID.randomUUID(); ids.add(id);
                admin.update("INSERT INTO applications(id,owner_id,company,role,location,status,notes,applied_date,deadline,follow_up_date,created_at,updated_at,sample) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                        id, owner, List.of("alpha", "Beta", "zeta").get(i), "cohort", "Remote", List.of("OFFER", "SAVED", "APPLIED").get(i), "literal %_\\",
                        i == 2 ? null : LocalDate.parse("2026-02-02").minusDays(i), i == 2 ? null : LocalDate.parse("2026-11-01").plusDays(i), LocalDate.parse("2026-11-01"), now, now, i == 1);
            }
            var stage = UUID.randomUUID();
            admin.update("INSERT INTO interview_stages(id,application_id,position,name,stage_date,state) VALUES(?,?,0,'Systems %_',?,'SCHEDULED')", stage, ids.get(0), LocalDate.parse("2026-11-02"));
            admin.update("INSERT INTO interview_stages(id,application_id,position,name,stage_date,state) SELECT md5(CAST(id AS text)||'stage')::uuid,id,0,'Skew round',?,'SCHEDULED' FROM applications WHERE role='Intern'", LocalDate.parse("2026-11-02"));
            admin.execute("ANALYZE applications"); admin.execute("ANALYZE interview_stages");
            var jdbc = new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), "campus_runtime", "runtime-'quote-password"));
            var repository = new ApplicationReadRepository(jdbc);
            var cursors = new ApplicationCursorCodec(new ObjectMapper().findAndRegisterModules());
            var clock = Clock.fixed(now.toInstant(), ZoneOffset.UTC);
            var service = new ApplicationReadService(repository, cursors, clock);
            for (String sort : List.of("company", "deadline", "applied")) {
                var companies = new ArrayList<String>(); String cursor = null;
                do {
                    var result = service.list(owner, new ApplicationFilters("cohort", null, "all", false, null, sort, 1, cursor));
                    assertThat(result.content()).hasSize(1); companies.add(result.content().get(0).company()); cursor = result.nextCursor();
                } while (cursor != null);
                assertThat(companies).containsExactly("alpha", "Beta", "zeta");
            }
            assertThat(service.list(owner, new ApplicationFilters("%_\\", null, "all", false, null, "updated", 8, null)).content()).hasSize(3);
            assertThat(service.list(owner, new ApplicationFilters("systems", null, "all", false, null, "updated", 8, null)).content()).extracting(ApplicationListItem::id).containsExactly(ids.get(0));
            var page = service.list(owner, new ApplicationFilters("", null, "all", false, null, "updated", 8, null));
            assertThat(page.content()).hasSize(8); assertThat(page.hasNext()).isTrue();
            var summary = new WorkspaceSummaryService(repository, clock).summary(owner, LocalDate.parse("2026-11-01"));
            assertThat(summary.total()).isEqualTo(5003); assertThat(summary.due()).isEqualTo(3);
            assertThat(summary.offers()).isEqualTo(1); assertThat(summary.scheduledInterviews()).isEqualTo(5001); assertThat(summary.hasSamples()).isTrue();
            var activities = new ActivityReadService(repository, cursors, clock).activities(owner, null, "cohort", null, 25, null);
            assertThat(activities.content()).hasSize(5);
            // V1 already supports the required descending tuple through a backward index scan.
            String plan = String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT id,company,role,location,status,applied_date,deadline,follow_up_date,created_at,updated_at,version,sample FROM applications WHERE owner_id=? AND deleted=false ORDER BY updated_at DESC,id DESC LIMIT 9", String.class, owner));
            assertThat(plan).contains("Index Scan Backward using idx_app_owner_updated");
            String activityPlan = String.join("\n", jdbc.queryForList("EXPLAIN (ANALYZE,BUFFERS) SELECT s.id FROM interview_stages s JOIN applications a ON a.id=s.application_id WHERE a.owner_id=? AND a.deleted=false AND s.state='SCHEDULED' AND s.stage_date IS NOT NULL ORDER BY s.stage_date,s.id LIMIT 26", String.class, owner));
            Files.writeString(Path.of("target/workspace-query-plans.txt"), "Default list using existing V1 order index:\n" + plan + "\n\nScheduled activities:\n" + activityPlan);
        }
    }
}
