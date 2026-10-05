package com.campus.tracker.mail;

import java.util.concurrent.Semaphore;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.LoggerFactory;

/** No executor queue and no claim before a delivery thread is available. */
public final class MailWorkDispatcher implements AutoCloseable {
    private final EmailOutboxWorker worker;
    private final Semaphore available;
    private final ThreadPoolExecutor executor;
    private final int concurrency;
    public MailWorkDispatcher(EmailOutboxWorker worker, int concurrency) {
        if (concurrency < 1 || concurrency > 32) throw new IllegalArgumentException("Invalid mail worker concurrency");
        this.worker = worker; this.concurrency = concurrency; available = new Semaphore(concurrency);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        executor = new ThreadPoolExecutor(concurrency, concurrency, 0, TimeUnit.MILLISECONDS, new SynchronousQueue<>(),
                task -> new Thread(task, "campus-mail-" + sequence.incrementAndGet()), new ThreadPoolExecutor.AbortPolicy());
    }
    public void poll() {
        for (int i = 0; i < concurrency && available.tryAcquire(); i++) {
            try {
                executor.execute(() -> {
                    try { worker.deliverOne(); }
                    catch (RuntimeException failure) { LoggerFactory.getLogger(MailWorkDispatcher.class).warn("Mail worker iteration failed; retry on next poll"); }
                    finally { available.release(); }
                });
            } catch (RejectedExecutionException shutdownOrBusy) { available.release(); break; }
        }
    }
    @Override public void close() {
        executor.shutdown();
        try { if (!executor.awaitTermination(25, TimeUnit.SECONDS)) executor.shutdownNow(); }
        catch (InterruptedException interrupted) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
    }
}
