package com.campus.tracker.auth.abuse;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.slf4j.LoggerFactory;

/** Bounded process-local buckets. Active restrictions are never evicted to admit new identities. */
public final class AuthRateLimiter implements AuthAdmissionLimiter {
    public record Policy(int capacity, Duration refillPeriod) {
        public Policy {
            if (capacity < 1 || capacity > 1_000_000 || refillPeriod == null
                    || refillPeriod.isZero() || refillPeriod.isNegative()
                    || refillPeriod.compareTo(Duration.ofDays(1)) > 0)
                throw new IllegalArgumentException("Invalid authentication bucket policy");
        }
    }
    public record Decision(boolean allowed, long retryAfterSeconds) {}
    private static final Decision ALLOWED = new Decision(true, 0);
    private static final class Bucket {
        final Policy policy;
        double tokens;
        long updated;
        Bucket(Policy policy, long now) { this.policy = policy; tokens = policy.capacity(); updated = now; }
        void refill(long now) {
            long elapsed = now - updated;
            if (elapsed > 0) {
                tokens = Math.min(policy.capacity(), tokens + (double) elapsed / policy.refillPeriod().toNanos() * policy.capacity());
                updated = now;
            }
        }
        long waitSeconds() {
            return Math.max(1, (long) Math.ceil((1 - tokens) * policy.refillPeriod().toNanos() / policy.capacity() / 1_000_000_000d));
        }
    }
    private final int maximumKeys;
    private final LongSupplier ticker;
    private final Map<String, Bucket> buckets = new HashMap<>();
    private long lastSweep;
    private boolean swept;
    private long rejected;

    public AuthRateLimiter(int maximumKeys, LongSupplier ticker) {
        if (maximumKeys < 1 || maximumKeys > 1_000_000) throw new IllegalArgumentException("Invalid limiter capacity");
        this.maximumKeys = maximumKeys; this.ticker = Objects.requireNonNull(ticker);
    }
    public synchronized Decision acquire(String key, Policy policy) {
        if (key == null || key.length() > 512) throw new IllegalArgumentException("Invalid limiter key");
        Objects.requireNonNull(policy);
        long now = ticker.getAsLong();
        Bucket bucket = buckets.get(key);
        if (bucket == null) {
            // Bound cleanup CPU as well as memory under random-key floods.
            if (buckets.size() >= maximumKeys && (!swept || now - lastSweep >= 1_000_000_000L)) {
                buckets.values().removeIf(value -> { value.refill(now); return value.tokens >= value.policy.capacity(); });
                lastSweep = now; swept = true;
            }
            if (buckets.size() >= maximumKeys) return reject(1);
            bucket = new Bucket(policy, now); buckets.put(key, bucket);
        }
        bucket.refill(now);
        if (bucket.tokens < 1) return reject(bucket.waitSeconds());
        bucket.tokens--; return ALLOWED;
    }
    private Decision reject(long seconds) {
        rejected++;
        if (rejected == 1 || rejected % 1000 == 0)
            LoggerFactory.getLogger(AuthRateLimiter.class).info("Authentication rate-limit rejections: {}", rejected);
        return new Decision(false, seconds);
    }
}
