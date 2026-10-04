package com.campus.tracker.auth.abuse;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;

class AuthRateLimiterTest {
    @Test void rejectsUntilRefilled() {
        var time = new AtomicLong();
        var limiter = new AuthRateLimiter(100, time::get);
        var policy = new AuthRateLimiter.Policy(2, Duration.ofMinutes(1));
        assertThat(limiter.acquire("ip", policy).allowed()).isTrue();
        assertThat(limiter.acquire("ip", policy).allowed()).isTrue();
        assertThat(limiter.acquire("ip", policy).retryAfterSeconds()).isEqualTo(30);
        time.addAndGet(Duration.ofSeconds(29).toNanos());
        assertThat(limiter.acquire("ip", policy).allowed()).isFalse();
        time.addAndGet(Duration.ofSeconds(1).toNanos());
        assertThat(limiter.acquire("ip", policy).allowed()).isTrue();
    }
    @Test void newIdentitiesCannotEvictAnActiveRestriction() {
        var time = new AtomicLong();
        var limiter = new AuthRateLimiter(1, time::get);
        var policy = new AuthRateLimiter.Policy(1, Duration.ofMinutes(1));
        assertThat(limiter.acquire("original", policy).allowed()).isTrue();
        for (int i = 0; i < 50; i++) assertThat(limiter.acquire("rotated" + i, policy).allowed()).isFalse();
        assertThat(limiter.acquire("original", policy).allowed()).isFalse();
        time.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(limiter.acquire("new", policy).allowed()).isTrue();
    }
    @Test void concurrentRequestsCannotExceedCapacity() throws Exception {
        var limiter = new AuthRateLimiter(100, () -> 0);
        var policy = new AuthRateLimiter.Policy(7, Duration.ofHours(1));
        var executor = Executors.newFixedThreadPool(12);
        try {
            var tasks = java.util.stream.IntStream.range(0, 100)
                    .<Callable<Boolean>>mapToObj(i -> () -> limiter.acquire("shared", policy).allowed()).toList();
            int allowed = 0;
            for (var result : executor.invokeAll(tasks)) if (result.get()) allowed++;
            assertThat(allowed).isEqualTo(7);
        } finally { executor.shutdownNow(); }
    }
    @Test void invalidPoliciesFailAtStartup() {
        assertThatThrownBy(() -> new AuthRateLimiter.Policy(0, Duration.ofSeconds(1))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthRateLimiter.Policy(1, Duration.ZERO)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthRateLimiter(0, System::nanoTime)).isInstanceOf(IllegalArgumentException.class);
    }
}
