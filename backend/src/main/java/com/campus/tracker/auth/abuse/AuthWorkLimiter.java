package com.campus.tracker.auth.abuse;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.LoggerFactory;

public final class AuthWorkLimiter {
    private final Semaphore permits;
    private final AtomicLong rejected = new AtomicLong();
    public AuthWorkLimiter(int maximumActive) {
        if (maximumActive < 1) throw new IllegalArgumentException("Invalid authentication concurrency");
        permits = new Semaphore(maximumActive);
    }
    public Permit tryAcquire() {
        if (permits.tryAcquire()) return new Permit();
        long count = rejected.incrementAndGet();
        if (count == 1 || count % 1000 == 0)
            LoggerFactory.getLogger(AuthWorkLimiter.class).info("Authentication work-limit rejections: {}", count);
        return null;
    }
    public final class Permit implements AutoCloseable {
        private final AtomicBoolean closed = new AtomicBoolean();
        private Permit() {}
        @Override public void close() { if (closed.compareAndSet(false, true)) permits.release(); }
    }
}
