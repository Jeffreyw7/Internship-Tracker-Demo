package com.campus.tracker.support;

import com.campus.tracker.export.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;

/** Test-only subprocess: stop after a real private upload, before the READY commit. */
public class ExportKillHarness {
    public static void main(String[] args) throws Exception {
        var source = new DriverManagerDataSource(args[0], "campus_export_worker", args[1]);
        var clock = Clock.fixed(Instant.parse(args[4]), ZoneOffset.UTC);
        var limits = new ExportProperties(2, 104857600, Duration.ofHours(24), "file", Path.of(args[2]), Path.of(args[2]).resolveSibling("child-spool"), false);
        var files = new FileExportObjectStore(limits.fileRoot());
        var pauseAfterUpload = new ExportObjectStore() {
            public void put(String key, Path file) throws IOException {
                files.put(key, file); Files.writeString(Path.of(args[3]), key);
                try { Thread.sleep(Duration.ofHours(1).toMillis()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IOException("Test interrupted"); }
            }
            public InputStream open(String key) throws IOException { return files.open(key); }
            public void delete(String key) throws IOException { files.delete(key); }
        };
        try (var worker = new ExportWorker(new ExportJobRepository(new JdbcTemplate(source), new DataSourceTransactionManager(source), clock), pauseAfterUpload, new CsvWriter(), limits, clock)) { worker.tick(); }
    }
}
