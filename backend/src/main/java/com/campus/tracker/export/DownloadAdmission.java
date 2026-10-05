package com.campus.tracker.export;

import java.io.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import com.campus.tracker.common.ApiException;
import org.springframework.http.HttpStatus;

/** Bound open storage connections; disconnected/finished responses release their permit exactly once. */
public final class DownloadAdmission {
    private final Semaphore permits;
    public DownloadAdmission(int maximum) {
        if (maximum < 1 || maximum > 32) throw new IllegalArgumentException("Invalid export download capacity");
        permits = new Semaphore(maximum);
    }
    public interface Open { InputStream get() throws IOException; }
    public InputStream open(Open storage) throws IOException {
        if (!permits.tryAcquire()) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Export download capacity is busy. Try again.");
        try {
            return new FilterInputStream(storage.get()) {
                private final AtomicBoolean closed = new AtomicBoolean();
                @Override public void close() throws IOException {
                    if (closed.compareAndSet(false, true)) try { super.close(); } finally { permits.release(); }
                }
            };
        } catch (IOException | RuntimeException failure) { permits.release(); throw failure; }
    }
}
