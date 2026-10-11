package com.campus.tracker.auth.reset;

import com.campus.tracker.auth.abuse.AuthThrottleException;
import org.springframework.stereotype.Component;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/** Admission never queues unbounded work. Permits are not database connections. */
@Component
public final class RecoveryCapacity {
    private final Semaphore ingress=new Semaphore(4);
    private final Semaphore database=new Semaphore(8);
    public Permit ingress() { return acquire(ingress); }
    public Permit database() { return acquire(database); }
    private Permit acquire(Semaphore permits) {
        if (!permits.tryAcquire()) throw new AuthThrottleException(1);
        return new Permit(permits);
    }
    public static final class Permit implements AutoCloseable {
        private final Semaphore permits;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Permit(Semaphore permits) { this.permits=permits; }
        public void close() { if (closed.compareAndSet(false,true)) permits.release(); }
    }
}
