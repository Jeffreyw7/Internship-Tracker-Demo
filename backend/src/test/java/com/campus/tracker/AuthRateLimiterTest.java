package com.campus.tracker;

import com.campus.tracker.auth.verification.AuthRateLimiter;
import com.campus.tracker.common.ApiException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

class AuthRateLimiterTest {
    @Test void aBucketCreatedBetweenUpdateAndReadStillAdmitsAnAvailableAttempt() throws Exception {
        var source = source();
        var missed = new CountDownLatch(1);
        var created = new CountDownLatch(1);
        var jdbc = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                int result = super.update(sql, args);
                if (result == 0 && sql.startsWith("UPDATE") && "request-minute".equals(args[0])) {
                    missed.countDown();await(created);
                }
                return result;
            }
        };
        var delayed = transactionalLimiter(jdbc, source);
        var winner = transactionalLimiter(new JdbcTemplate(source), source);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var attempt = pool.submit(() -> delayed.requireRequestAllowed("192.0.2.50"));
            assertThat(missed.await(5, TimeUnit.SECONDS)).isTrue();
            winner.requireRequestAllowed("192.0.2.50");created.countDown();
            assertThatCode(() -> attempt.get(5, TimeUnit.SECONDS)).doesNotThrowAnyException();
            assertThat(jdbc.queryForList("SELECT attempts FROM auth_rate_limits", Integer.class)).containsExactlyInAnyOrder(2, 2);
        } finally {created.countDown();pool.shutdownNow();}
    }
    @Test void aLockedBucketDoesNotBlockUnrelatedRequests() throws Exception {
        var source = source();
        var reachedUpdate = new CountDownLatch(1);
        var blockedThread = new AtomicReference<Thread>();
        var jdbc = new JdbcTemplate(source) {
            @Override public int update(String sql, Object... args) {
                if (Thread.currentThread() == blockedThread.get()) reachedUpdate.countDown();
                return super.update(sql, args);
            }
        };
        var limiter = transactionalLimiter(jdbc, source);
        limiter.requireRequestAllowed("192.0.2.10");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(new DataSourceTransactionManager(source)).execute(s -> {
                jdbc.update("UPDATE auth_rate_limits SET attempts=attempts WHERE scope='request-minute'");
                locked.countDown();await(release);return null;
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var blocked = pool.submit(() -> {blockedThread.set(Thread.currentThread());limiter.requireRequestAllowed("192.0.2.10");});
            assertThat(reachedUpdate.await(5, TimeUnit.SECONDS)).isTrue();
            var unrelated = pool.submit(() -> limiter.requireRequestAllowed("192.0.2.20"));
            try {assertThatCode(() -> unrelated.get(1, TimeUnit.SECONDS)).doesNotThrowAnyException();}
            finally {release.countDown();}
            holder.get(5, TimeUnit.SECONDS);blocked.get(5, TimeUnit.SECONDS);
        } finally {release.countDown();pool.shutdownNow();}
    }

    @Test void separateInstancesCanRaceToCreateTheSameBucket() throws Exception {
        var source = source();
        var barrier = new CyclicBarrier(2);
        var jdbc = new JdbcTemplate(source) {
            @Override public <T> T query(String sql, ResultSetExtractor<T> extractor, Object... args) {
                T result = super.query(sql, extractor, args);
                if (result == null && args.length > 0 && "request-minute".equals(args[0])) {
                    try {barrier.await(5, TimeUnit.SECONDS);} catch (Exception e) {throw new IllegalStateException(e);}
                }
                return result;
            }
        };
        var first = transactionalLimiter(jdbc, source);
        var second = transactionalLimiter(jdbc, source);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> first.requireRequestAllowed("192.0.2.30"));
            var b = pool.submit(() -> second.requireRequestAllowed("192.0.2.30"));
            assertThatCode(() -> {a.get(10, TimeUnit.SECONDS);b.get(10, TimeUnit.SECONDS);}).doesNotThrowAnyException();
            assertThat(jdbc.queryForList("SELECT attempts FROM auth_rate_limits", Integer.class)).containsExactlyInAnyOrder(2, 2);
        } finally {pool.shutdownNow();}
    }

    @Test void concurrentRequestsCannotExceedTheMinuteBudget() throws Exception {
        var source = source();
        var limiter = transactionalLimiter(new JdbcTemplate(source), source);
        var pool = Executors.newFixedThreadPool(12);
        var start = new CountDownLatch(1);
        try {
            var requests = new java.util.ArrayList<Future<Boolean>>();
            for (int i=0;i<12;i++) requests.add(pool.submit(() -> {
                await(start);
                try {limiter.requireRequestAllowed("192.0.2.40");return true;}
                catch (ApiException e) {assertThat(e.getStatus().value()).isEqualTo(429);return false;}
            }));
            start.countDown();int accepted=0;
            for (var request : requests) if (request.get(10, TimeUnit.SECONDS)) accepted++;
            assertThat(accepted).isEqualTo(5);
        } finally {pool.shutdownNow();}
    }

    private DriverManagerDataSource source() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:concurrent-rate-" + UUID.randomUUID()
            + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();return source;
    }
    private AuthRateLimiter transactionalLimiter(JdbcTemplate jdbc, DriverManagerDataSource source) {
        var proxy = new ProxyFactory(new AuthRateLimiter(jdbc, new MutableClock(), "test-rate-limit-secret-long-enough", true));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source), new AnnotationTransactionAttributeSource()));
        return (AuthRateLimiter) proxy.getProxy();
    }
    private static void await(CountDownLatch latch) {
        try {if (!latch.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out coordinating requests");}
        catch (InterruptedException e) {Thread.currentThread().interrupt();throw new IllegalStateException(e);}
    }
    @Test void addressBudgetsAreBoundedAndIdentifiersAreNotStoredInPlaintext() {
        MutableClock clock = new MutableClock();
        var source = new DriverManagerDataSource("jdbc:h2:mem:rate-" + UUID.randomUUID()
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        Flyway.configure().dataSource(source).load().migrate();
        JdbcTemplate jdbc = new JdbcTemplate(source);
        AuthRateLimiter limiter = new AuthRateLimiter(jdbc, clock,
                "test-rate-limit-secret-long-enough", true);

        assertThat(limiter.reserveEmail("student@example.test")).isTrue();
        assertThat(limiter.reserveEmail("student@example.test")).isFalse();
        assertThat(jdbc.queryForList("SELECT subject_hash FROM auth_rate_limits", String.class))
                .allSatisfy(value -> assertThat(value).doesNotContain("student", "example.test").hasSize(64));

        clock.advance(Duration.ofSeconds(61));
        assertThat(limiter.reserveEmail("student@example.test")).isTrue();
        for (int i = 0; i < 5; i++) limiter.requireRequestAllowed("192.0.2.10");
        assertThatThrownBy(() -> limiter.requireRequestAllowed("192.0.2.10"))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).getStatus().value()).isEqualTo(429);
    }

    private static final class MutableClock extends Clock {
        private Instant current = Instant.parse("2026-09-25T00:00:00Z");
        void advance(Duration duration) { current = current.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return current; }
    }
}
