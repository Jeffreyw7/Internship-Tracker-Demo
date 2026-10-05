package com.campus.tracker;

import com.campus.tracker.export.*;
import com.campus.tracker.migration.MigrationMain;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ExportWorkerIT {
    @TempDir Path directory;
    @Test void concurrentApiAdmissionAndRestrictedWorkerTakeoverUseRealPostgres() throws Exception {
        try (var database = new PostgresPrivilegesIT.TestDatabase(false)) {
            var provision = database.psql("psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", database.getUsername(), "-d", "campus", "-f", "provision.sql");
            assertThat(provision.getExitCode()).withFailMessage(provision.getStderr()).isZero();
            MigrationMain.run(Map.of("MIGRATION_DATABASE_URL", database.getJdbcUrl(), "MIGRATION_DATABASE_USERNAME", "campus_migrator", "MIGRATION_DATABASE_PASSWORD", "migration-'quote-password"));
            var adminSource = new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword());
            var admin = new JdbcTemplate(adminSource);
            var clock = new MutableClock();
            var now = clock.instant().atOffset(ZoneOffset.UTC);
            var owner = UUID.randomUUID();
            admin.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", owner, owner + "@example.test", "Export", "synthetic", now);
            UUID app = UUID.randomUUID();
            admin.update("INSERT INTO applications(id,owner_id,company,role,location,status,notes,created_at,updated_at) VALUES(?,?,?,?,?,'APPLIED',?,?,?)", app, owner, "Restricted output", "Intern", "Remote", "private notes", now.minusSeconds(1), now);
            var limits = new ExportProperties(2, 104857600, Duration.ofHours(24), "file", directory.resolve("objects"), directory.resolve("spool"), false);
            var apiSource = new DriverManagerDataSource(database.getJdbcUrl(), "campus_runtime", "runtime-'quote-password");
            var api = new ExportService(new ExportRepository(new JdbcTemplate(apiSource)), limits, new FileExportObjectStore(limits.fileRoot()), clock);
            var transactions = new TransactionTemplate(new DataSourceTransactionManager(apiSource));
            var pool = Executors.newFixedThreadPool(8);
            try {
                var results = java.util.stream.IntStream.range(0, 24).mapToObj(index -> pool.submit(() -> {
                    try { transactions.execute(status -> api.create(owner)); return true; }
                    catch (com.campus.tracker.common.ApiException rejected) { return false; }
                })).toList();
                int accepted = 0; for (var result : results) if (result.get(20, TimeUnit.SECONDS)) accepted++;
                assertThat(accepted).isEqualTo(2);
            } finally { pool.shutdownNow(); }
            var workerSource = new DriverManagerDataSource(database.getJdbcUrl(), "campus_export_worker", "export-worker-'quote-password");
            var workerJdbc = new JdbcTemplate(workerSource); workerJdbc.setQueryTimeout(2);
            var first = new ExportJobRepository(workerJdbc, new DataSourceTransactionManager(workerSource), clock);
            var second = new ExportJobRepository(workerJdbc, new DataSourceTransactionManager(workerSource), clock);
            UUID locked = admin.queryForObject("SELECT id FROM export_jobs ORDER BY next_attempt_at,created_at,id LIMIT 1", UUID.class);
            try (var connection = adminSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.prepareStatement("SELECT id FROM export_jobs WHERE id=? FOR UPDATE")) { statement.setObject(1, locked); statement.executeQuery().close(); }
                var available = second.claim(UUID.randomUUID());
                assertThat(available).isNotNull(); assertThat(available.id()).isNotEqualTo(locked);
                connection.rollback();
            }
            var old = first.claim(UUID.randomUUID()); assertThat(old.id()).isEqualTo(locked);
            assertThat(second.claim(UUID.randomUUID())).isNull();
            clock.advance(Duration.ofMinutes(3));
            var replacement = second.claim(UUID.randomUUID());
            assertThat(first.complete(old, "jobs/" + old.id() + "/" + old.leaseOwner() + ".csv", 0)).isFalse();
            var objects = new FileExportObjectStore(limits.fileRoot());
            try (var worker = new ExportWorker(second, objects, new CsvWriter(), limits, clock)) { worker.process(replacement); worker.tick(); worker.maintain(); }
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM export_jobs WHERE state='READY'", Integer.class)).isEqualTo(2);
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM applications", Integer.class)).isEqualTo(1);
            clock.advance(Duration.ofDays(9));
            try (var worker = new ExportWorker(second, objects, new CsvWriter(), limits, clock)) { worker.maintain(); }
            assertThat(admin.queryForObject("SELECT COUNT(*) FROM export_jobs", Integer.class)).isZero();
            // Terminate an actual worker JVM between object publication and database completion.
            transactions.execute(status -> api.create(owner));
            Path signal = directory.resolve("uploaded.txt");
            String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            Path argumentFile = directory.resolve("worker.args");
            var arguments = List.of("-cp", classpath, "com.campus.tracker.support.ExportKillHarness", database.getJdbcUrl(),
                    "export-worker-'quote-password", limits.fileRoot().toString(), signal.toString(), clock.instant().toString());
            java.nio.file.Files.writeString(argumentFile, arguments.stream().map(value -> "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"").collect(java.util.stream.Collectors.joining("\n")));
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + argumentFile)
                    .redirectErrorStream(true).redirectOutput(directory.resolve("child.log").toFile()).start();
            try {
                long waitUntil = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                while (!java.nio.file.Files.exists(signal) && process.isAlive() && System.nanoTime() < waitUntil) Thread.sleep(50);
                assertThat(signal).withFailMessage("Worker did not reach upload: %s", java.nio.file.Files.readString(directory.resolve("child.log"))).exists();
                String abandoned = java.nio.file.Files.readString(signal);
                assertThat(admin.queryForObject("SELECT COUNT(*) FROM export_jobs WHERE state='RUNNING'", Integer.class)).isEqualTo(1);
                process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue();
                clock.advance(Duration.ofMinutes(3));
                try (var worker = new ExportWorker(second, objects, new CsvWriter(), limits, clock)) { worker.tick(); clock.advance(Duration.ofMinutes(11)); worker.maintain(); }
                assertThat(admin.queryForObject("SELECT COUNT(*) FROM export_jobs WHERE state='READY'", Integer.class)).isEqualTo(1);
                assertThatThrownBy(() -> objects.open(abandoned)).isInstanceOf(java.io.IOException.class);
            } finally { if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); } }
        }
    }
    private static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-05T12:00:00Z");
        void advance(Duration value) { now = now.plus(value); }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
