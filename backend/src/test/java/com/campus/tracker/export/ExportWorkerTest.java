package com.campus.tracker.export;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class ExportWorkerTest {
    @TempDir Path directory;
    JdbcTemplate jdbc;
    TransactionTemplate transactions;
    ExportRepository api;
    ExportJobRepository jobs;
    ExportProperties limits;
    MutableClock clock;
    UUID owner;
    @BeforeEach void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:export" + UUID.randomUUID() + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
        var manager = new DataSourceTransactionManager(source);
        transactions = new TransactionTemplate(manager);
        clock = new MutableClock();
        limits = new ExportProperties(2, 104857600, Duration.ofHours(24), "file", directory.resolve("objects"), directory.resolve("spool"), false);
        api = new ExportRepository(jdbc);
        jobs = new ExportJobRepository(jdbc, manager, clock);
        owner = UUID.randomUUID();
        jdbc.update("INSERT INTO campus_users(id,email,display_name,password_hash,created_at) VALUES(?,?,?,?,?)", owner, owner + "@example.test", "Export", "synthetic", ExportRepository.at(clock.instant()));
    }
    ExportRepository.Job create() { return transactions.execute(status -> api.create(owner, clock.instant(), limits)); }
    UUID application(String company, Instant created) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO applications(id,owner_id,company,role,location,status,notes,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?)",
                id, owner, company, "Intern", "Remote", "APPLIED", "quoted \"notes\"\nline", ExportRepository.at(created), ExportRepository.at(created));
        return id;
    }
    @Test void producesFullHistoryWithoutLaterCreationsAndPublishesOnlyCompleteObjects() throws Exception {
        for (int i = 0; i < 205; i++) application("Company " + i, clock.instant().minusSeconds(1));
        UUID staged = application("=Formula", clock.instant().minusSeconds(1));
        jdbc.update("INSERT INTO interview_stages(id,application_id,position,name,state,stage_date) VALUES(?,?,0,'Technical','SCHEDULED',DATE '2026-10-06')", UUID.randomUUID(), staged);
        var job = create();
        application("Too late", clock.instant().plusSeconds(1));
        var objects = new FileExportObjectStore(limits.fileRoot());
        try (var worker = new ExportWorker(jobs, objects, new CsvWriter(), limits, clock)) { worker.tick(); }
        var ready = api.owned(owner, job.id());
        assertThat(ready.state()).isEqualTo(ExportDtos.State.READY);
        try (var input = objects.open(ready.objectKey())) {
            String csv = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(csv).startsWith("\uFEFF\"Company\"").contains("Company 204", "'=" + "Formula", "Technical | Scheduled | 2026-10-06", "quoted \"\"notes\"\"\nline").doesNotContain("Too late");
            assertThat(csv.split("\\r\\n")).hasSize(207);
        }
        try (var spools = Files.list(limits.spoolRoot())) { assertThat(spools).isEmpty(); }
    }
    @Test void takeoverFencesOldCompletionAndCleanupPreservesPublishedOutput() throws Exception {
        application("One", clock.instant().minusSeconds(1));
        var job = create();
        var first = jobs.claim(UUID.randomUUID());
        String orphan = "jobs/" + job.id() + "/" + first.leaseOwner() + ".csv";
        assertThat(jobs.beginPublication(first, orphan)).isTrue();
        var objects = new FileExportObjectStore(limits.fileRoot());
        Path spool = directory.resolve("orphan.csv"); Files.writeString(spool, "orphan"); objects.put(orphan, spool);
        clock.advance(Duration.ofMinutes(3));
        var second = jobs.claim(UUID.randomUUID());
        assertThat(second.id()).isEqualTo(job.id());
        assertThat(jobs.complete(first, orphan, 6)).isFalse();
        try (var worker = new ExportWorker(jobs, objects, new CsvWriter(), limits, clock)) { worker.process(second); clock.advance(Duration.ofMinutes(11)); worker.maintain(); }
        assertThatThrownBy(() -> objects.open(orphan)).isInstanceOf(IOException.class);
        var ready = api.owned(owner, job.id());
        try (var input = objects.open(ready.objectKey())) { assertThat(input.readAllBytes()).isNotEmpty(); }
    }
    @Test void cancellationDuringUploadCannotPublishAndAbandonedObjectIsCollected() throws Exception {
        application("One", clock.instant().minusSeconds(1)); var job = create();
        var files = new FileExportObjectStore(limits.fileRoot());
        var objects = new ExportObjectStore() {
            public void put(String key, Path file) throws IOException { files.put(key, file); api.cancel(owner, job.id()); }
            public InputStream open(String key) throws IOException { return files.open(key); }
            public void delete(String key) throws IOException { files.delete(key); }
        };
        try (var worker = new ExportWorker(jobs, objects, new CsvWriter(), limits, clock)) { worker.tick(); clock.advance(Duration.ofMinutes(11)); worker.maintain(); }
        assertThat(api.owned(owner, job.id()).state()).isEqualTo(ExportDtos.State.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_objects", Integer.class)).isZero();
    }
    @Test void oversizedOutputFailsSafelyAndFailedUploadIsDurablyRetried() throws Exception {
        for (int i = 0; i < 30; i++) application("Long company " + i, clock.instant().minusSeconds(1));
        var small = new ExportProperties(2, 1024, Duration.ofHours(24), "file", limits.fileRoot(), limits.spoolRoot(), false);
        var job = create();
        try (var worker = new ExportWorker(jobs, new FileExportObjectStore(limits.fileRoot()), new CsvWriter(), small, clock)) { worker.tick(); }
        assertThat(api.owned(owner, job.id()).failureCode()).isEqualTo("SIZE_LIMIT");
        var retry = create();
        var unavailable = new ExportObjectStore() {
            public void put(String key, Path file) throws IOException { throw new IOException("private endpoint"); }
            public InputStream open(String key) throws IOException { throw new IOException(); }
            public void delete(String key) throws IOException { throw new IOException(); }
        };
        try (var worker = new ExportWorker(jobs, unavailable, new CsvWriter(), limits, clock)) {
            worker.tick(); assertThat(api.owned(owner, retry.id()).state()).isEqualTo(ExportDtos.State.PENDING);
            assertThat(jobs.claim(UUID.randomUUID())).isNull();
        }
        clock.advance(Duration.ofMinutes(2));
        try (var worker = new ExportWorker(jobs, new FileExportObjectStore(limits.fileRoot()), new CsvWriter(), limits, clock)) { worker.tick(); }
        assertThat(api.owned(owner, retry.id()).state()).isEqualTo(ExportDtos.State.READY);
    }
    @Test void ambiguousDatabaseCompletionNeverDeletesAnAlreadyPublishedObject() throws Exception {
        application("One", clock.instant().minusSeconds(1)); var job = create();
        var uncertain = new ExportJobRepository(jdbc, new DataSourceTransactionManager(jdbc.getDataSource()), clock) {
            @Override public boolean complete(ExportRepository.Job claimed, String key, long bytes) {
                super.complete(claimed, key, bytes);
                throw new org.springframework.dao.TransientDataAccessResourceException("commit acknowledgement lost");
            }
        };
        var objects = new FileExportObjectStore(limits.fileRoot());
        try (var worker = new ExportWorker(uncertain, objects, new CsvWriter(), limits, clock)) { worker.tick(); clock.advance(Duration.ofMinutes(11)); worker.maintain(); }
        var ready = api.owned(owner, job.id());
        assertThat(ready.state()).isEqualTo(ExportDtos.State.READY);
        try (var input = objects.open(ready.objectKey())) { assertThat(input.readAllBytes()).isNotEmpty(); }
    }
    @Test void expiryCleanupRetainsTrackingUntilObjectDeletionSucceeds() throws Exception {
        application("One", clock.instant().minusSeconds(1)); var job = create();
        var files = new FileExportObjectStore(limits.fileRoot());
        try (var worker = new ExportWorker(jobs, files, new CsvWriter(), limits, clock)) { worker.tick(); }
        String key = api.owned(owner, job.id()).objectKey();
        clock.advance(Duration.ofHours(25));
        var failedDelete = new ExportObjectStore() {
            public void put(String value, Path file) throws IOException { files.put(value, file); }
            public InputStream open(String value) throws IOException { return files.open(value); }
            public void delete(String value) throws IOException { throw new IOException("provider offline"); }
        };
        try (var worker = new ExportWorker(jobs, failedDelete, new CsvWriter(), limits, clock)) { worker.maintain(); }
        assertThat(api.owned(owner, job.id()).state()).isEqualTo(ExportDtos.State.EXPIRED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_objects", Integer.class)).isEqualTo(1);
        try (var worker = new ExportWorker(jobs, files, new CsvWriter(), limits, clock)) { worker.maintain(); clock.advance(Duration.ofDays(8)); worker.maintain(); }
        assertThatThrownBy(() -> files.open(key)).isInstanceOf(IOException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM export_jobs", Integer.class)).isZero();
    }
    @Test void downloadsReleaseCapacityWhenClosedAndRejectExcessConcurrentStreams() throws Exception {
        application("One", clock.instant().minusSeconds(1)); var job = create();
        var files = new FileExportObjectStore(limits.fileRoot());
        try (var worker = new ExportWorker(jobs, files, new CsvWriter(), limits, clock)) { worker.tick(); }
        var service = new ExportService(api, limits, files, clock);
        var streams = new ArrayList<InputStream>();
        try {
            for (int i = 0; i < 4; i++) streams.add(service.download(owner, job.id()).resource().getInputStream());
            assertThatThrownBy(() -> service.download(owner, job.id())).isInstanceOf(com.campus.tracker.common.ApiException.class);
            streams.remove(0).close();
            streams.add(service.download(owner, job.id()).resource().getInputStream());
        } finally { for (var stream : streams) stream.close(); }
    }
    @Test void maintenanceRemovesOnlyAbandonedSpoolsWithinTheConfiguredDirectory() throws Exception {
        Files.createDirectories(limits.spoolRoot());
        Path abandoned = limits.spoolRoot().resolve("export-abandoned.csv"); Files.writeString(abandoned, "abandoned");
        Files.setLastModifiedTime(abandoned, java.nio.file.attribute.FileTime.from(clock.instant().minus(Duration.ofHours(2))));
        Path active = limits.spoolRoot().resolve("export-active.csv"); Files.writeString(active, "active");
        Files.setLastModifiedTime(active, java.nio.file.attribute.FileTime.from(clock.instant()));
        Path unrelated = limits.spoolRoot().resolve("notes.txt"); Files.writeString(unrelated, "unrelated");
        try (var worker = new ExportWorker(jobs, new FileExportObjectStore(limits.fileRoot()), new CsvWriter(), limits, clock)) { worker.maintain(); }
        assertThat(abandoned).doesNotExist(); assertThat(active).exists(); assertThat(unrelated).exists();
    }
    static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-05T12:00:00Z");
        void advance(Duration value) { now = now.plus(value); }
        public Instant instant() { return now; }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }
}
