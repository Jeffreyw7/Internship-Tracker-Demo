package com.campus.tracker.export;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

public class ExportWorker implements AutoCloseable {
    private final ExportJobRepository jobs;
    private final ExportObjectStore objects;
    private final CsvWriter csv;
    private final ExportProperties limits;
    private final Clock clock;
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "export-lease"); thread.setDaemon(true); return thread;
    });
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    public ExportWorker(ExportJobRepository jobs, ExportObjectStore objects, CsvWriter csv, ExportProperties limits, Clock clock) {
        this.jobs = jobs; this.objects = objects; this.csv = csv; this.limits = limits; this.clock = clock;
    }
    public void tick() {
        if (closing.get() || !busy.compareAndSet(false, true)) return;
        try { var job = jobs.claim(UUID.randomUUID()); if (job != null) process(job); }
        catch (RuntimeException failure) { LoggerFactory.getLogger(getClass()).warn("Export poll dependency failure"); }
        finally { busy.set(false); }
    }
    public void process(ExportRepository.Job job) {
        Path spool = null;
        var live = new AtomicBoolean(true);
        var heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try { if (!jobs.renew(job)) live.set(false); }
            catch (RuntimeException failure) { live.set(false); }
        }, 30, 30, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + Duration.ofMinutes(20).toNanos();
        try {
            Files.createDirectories(limits.spoolRoot());
            spool = Files.createTempFile(limits.spoolRoot(), "export-", ".csv");
            try (var output = new LimitedOutput(Files.newOutputStream(spool), limits.maxBytes())) {
                csv.header(output);
                ExportJobRepository.Cursor after = null;
                while (true) {
                    if (!live.get() || closing.get() || !jobs.renew(job)) return;
                    if (System.nanoTime() > deadline) throw new SizeOrDeadline("TIME_LIMIT");
                    var page = jobs.page(job, after);
                    if (page.isEmpty()) break;
                    for (var item : page) csv.row(output, item.row());
                    var last = page.get(page.size() - 1); after = new ExportJobRepository.Cursor(last.createdAt(), last.id());
                }
            }
            String key = "jobs/" + job.id() + "/" + job.leaseOwner() + ".csv";
            if (!live.get() || closing.get() || !jobs.beginPublication(job, key)) return;
            objects.put(key, spool);
            // An ambiguous completion exception may already have committed READY: durable cleanup decides later.
            jobs.complete(job, key, Files.size(spool));
        } catch (SizeOrDeadline failure) { jobs.fail(job, failure.code, false); }
        catch (IOException failure) { jobs.fail(job, "STORAGE_UNAVAILABLE", true); }
        catch (RuntimeException failure) { jobs.fail(job, "PROCESSING_UNAVAILABLE", true); }
        finally {
            heartbeat.cancel(false);
            if (spool != null) try { Files.deleteIfExists(spool); } catch (IOException failure) { LoggerFactory.getLogger(getClass()).warn("Export spool cleanup failure job={}", job.id()); }
        }
    }
    public void maintain() {
        jobs.expireBatch();
        for (var key : jobs.cleanupKeys()) {
            try { objects.delete(key); jobs.forgetObject(key); }
            catch (IOException | RuntimeException failure) { LoggerFactory.getLogger(getClass()).warn("Export object cleanup dependency failure"); }
        }
        jobs.purgeBatch();
        // A live job is capped at twenty minutes. Only older worker-created spools are abandoned.
        if (Files.isDirectory(limits.spoolRoot())) try (var files = Files.newDirectoryStream(limits.spoolRoot(), "export-*.csv")) {
            int inspected = 0;
            for (Path file : files) {
                if (++inspected > 100) break;
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant().isBefore(clock.instant().minus(Duration.ofHours(1))))
                    Files.deleteIfExists(file);
            }
        } catch (IOException failure) { LoggerFactory.getLogger(getClass()).warn("Export abandoned spool cleanup failure"); }
    }
    @Override public void close() { closing.set(true); heartbeats.shutdownNow(); }
    private static class SizeOrDeadline extends IOException {
        final String code;
        SizeOrDeadline(String code) { super(code); this.code = code; }
    }
    private static class LimitedOutput extends FilterOutputStream {
        private final long maximum;
        private long count;
        LimitedOutput(OutputStream output, long maximum) { super(output); this.maximum = maximum; }
        @Override public void write(int value) throws IOException { reserve(1); out.write(value); }
        @Override public void write(byte[] value, int offset, int length) throws IOException { reserve(length); out.write(value, offset, length); }
        private void reserve(int bytes) throws SizeOrDeadline { if (bytes > maximum - count) throw new SizeOrDeadline("SIZE_LIMIT"); count += bytes; }
    }
}
