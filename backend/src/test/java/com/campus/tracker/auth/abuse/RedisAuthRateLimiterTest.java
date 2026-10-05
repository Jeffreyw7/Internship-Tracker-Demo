package com.campus.tracker.auth.abuse;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class RedisAuthRateLimiterTest {
    private final AuthAdmissionProperties properties = new AuthAdmissionProperties("campus:test:admission", "test-only-shared-admission-secret-long-enough");
    private final AuthRateLimiter.Policy policy = new AuthRateLimiter.Policy(2, Duration.ofMinutes(1));

    @Test @SuppressWarnings({"unchecked", "rawtypes"}) void keysProtectIdentifiersAndKeepOneClusterSlot() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(List.of(0L, 4L));
        var result = new RedisAuthRateLimiter(redis, properties, 100).acquire("login-email:private@example.test", policy);
        assertThat(result).isEqualTo(new AuthRateLimiter.Decision(false, 4));
        ArgumentCaptor<List> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(any(RedisScript.class), keys.capture(), any(Object[].class));
        assertThat(keys.getValue().toString()).contains("{admission-v1}").doesNotContain("private", "@", "login-email");
    }

    @Test @SuppressWarnings("unchecked") void unavailableOrInvalidRedisReplyNeverAdmitsWork() {
        var redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisSystemException("OOM", new IllegalStateException()))
                .thenReturn(null).thenReturn(List.of(7L, 0L));
        var limiter = new RedisAuthRateLimiter(redis, properties, 100);
        for (int i = 0; i < 3; i++)
            assertThatThrownBy(() -> limiter.acquire("client", policy)).isInstanceOf(DataAccessResourceFailureException.class)
                    .hasMessage("Authentication admission temporarily unavailable");
    }
}
