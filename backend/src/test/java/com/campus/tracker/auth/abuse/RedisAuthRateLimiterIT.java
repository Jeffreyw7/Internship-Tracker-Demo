package com.campus.tracker.auth.abuse;

import com.campus.tracker.support.RedisFixture;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import static org.assertj.core.api.Assertions.*;

class RedisAuthRateLimiterIT {
    @Test void twoClientsShareOneAtomicBudgetAndRestartDoesNotResetRestrictions() throws Exception {
        try (var fixture = new RedisFixture(); var first = new Client(fixture); var second = new Client(fixture)) {
            var properties = properties();
            var a = new RedisAuthRateLimiter(first.redis, properties, 100);
            var b = new RedisAuthRateLimiter(second.redis, properties, 100);
            var policy = new AuthRateLimiter.Policy(7, Duration.ofHours(1));
            var pool = Executors.newFixedThreadPool(8);
            try {
                var tasks = java.util.stream.IntStream.range(0, 40).mapToObj(i ->
                        pool.submit(() -> (i % 2 == 0 ? a : b).acquire("same-client", policy))).toList();
                int admitted = 0;
                for (var task : tasks) if (task.get(10, TimeUnit.SECONDS).allowed()) admitted++;
                assertThat(admitted).isEqualTo(7);
                var restarted = new RedisAuthRateLimiter(second.redis, properties, 100);
                assertThat(restarted.acquire("same-client", policy).allowed()).isFalse();
            } finally { pool.shutdownNow(); }
        }
    }

    @Test void cardinalityDoesNotEvictRestrictionsAndReclaimsOnlyFullyRefilledBuckets() throws Exception {
        try (var fixture = new RedisFixture(); var client = new Client(fixture)) {
            var properties = properties();
            var limiter = new RedisAuthRateLimiter(client.redis, properties, 1);
            var policy = new AuthRateLimiter.Policy(1, Duration.ofMillis(500));
            assertThat(limiter.acquire("a", policy).allowed()).isTrue();
            assertThat(limiter.acquire("b", policy).allowed()).isFalse();
            assertThat(limiter.acquire("a", policy).allowed()).isFalse();
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            boolean admitted = false;
            while (System.nanoTime() < deadline && !(admitted = limiter.acquire("b", policy).allowed())) Thread.sleep(25);
            assertThat(admitted).isTrue();
            assertThat(client.redis.opsForZSet().zCard(properties.namespace() + ":{admission-v1}:keys")).isEqualTo(1);
            for (String key : client.redis.keys(properties.namespace() + ":*"))
                assertThat(client.redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive();
        }
    }

    @Test void fractionalRefillMatchesTheExistingTokenBucket() throws Exception {
        try (var fixture = new RedisFixture(); var client = new Client(fixture)) {
            var limiter = new RedisAuthRateLimiter(client.redis, properties(), 100);
            var policy = new AuthRateLimiter.Policy(2, Duration.ofSeconds(2));
            assertThat(limiter.acquire("client", policy).allowed()).isTrue();
            assertThat(limiter.acquire("client", policy).allowed()).isTrue();
            assertThat(limiter.acquire("client", policy).retryAfterSeconds()).isPositive();
            Thread.sleep(1100);
            assertThat(limiter.acquire("client", policy).allowed()).isTrue();
            assertThat(limiter.acquire("client", policy).allowed()).isFalse();
        }
    }

    @Test void redisNoevictionWriteFailureIsAnOutageNotAnAllowedAttemptOrQuotaDenial() throws Exception {
        try (var fixture = new RedisFixture(); var client = new Client(fixture)) {
            var limiter = new RedisAuthRateLimiter(client.redis, properties(), 100);
            config(client.redis, "maxmemory", "1");
            try {
                assertThatThrownBy(() -> limiter.acquire("client", new AuthRateLimiter.Policy(2, Duration.ofMinutes(1))))
                        .isInstanceOf(DataAccessResourceFailureException.class);
            } finally { config(client.redis, "maxmemory", "64mb"); }
            if (System.getProperty("campus.test.redis.port") == null) {
                fixture.stop();
                assertThatThrownBy(() -> limiter.acquire("client", new AuthRateLimiter.Policy(2, Duration.ofMinutes(1))))
                        .isInstanceOf(DataAccessResourceFailureException.class);
            }
        }
    }
    private void config(StringRedisTemplate redis, String key, String value) {
        redis.execute((RedisCallback<Object>) connection -> { connection.serverCommands().setConfig(key, value); return null; });
    }
    private AuthAdmissionProperties properties() {
        return new AuthAdmissionProperties("campus:test:" + UUID.randomUUID(), "test-only-shared-admission-secret-long-enough");
    }
    private static class Client implements AutoCloseable {
        final LettuceConnectionFactory factory;
        final StringRedisTemplate redis;
        Client(RedisFixture fixture) {
            var configuration = LettuceClientConfiguration.builder().commandTimeout(Duration.ofMillis(500))
                    .shutdownTimeout(Duration.ofMillis(100)).build();
            factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(fixture.host(), fixture.port()), configuration);
            factory.afterPropertiesSet(); factory.start();
            redis = new StringRedisTemplate(factory); redis.afterPropertiesSet();
        }
        @Override public void close() { factory.destroy(); }
    }
}
