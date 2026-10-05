package com.campus.tracker.auth.abuse;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.dao.DataAccessResourceFailureException;

/** Atomic, bounded shared admission. No infrastructure failure grants an attempt. */
public final class RedisAuthRateLimiter implements AuthAdmissionLimiter {
    private final StringRedisTemplate redis;
    private final String prefix;
    private final SecretKeySpec secret;
    private final int maximumKeys;
    private final DefaultRedisScript<List> script;

    public RedisAuthRateLimiter(StringRedisTemplate redis, AuthAdmissionProperties properties, int maximumKeys) {
        if (maximumKeys < 1 || maximumKeys > 1_000_000 || properties.namespace() == null
                || !properties.namespace().matches("[a-zA-Z0-9:_-]{1,100}")
                || properties.hmacSecret() == null || properties.hmacSecret().length() < 32)
            throw new IllegalArgumentException("Invalid shared admission configuration");
        this.redis = Objects.requireNonNull(redis);
        this.prefix = properties.namespace() + ":{admission-v1}";
        this.secret = new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.maximumKeys = maximumKeys;
        this.script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/auth-admission.lua"));
        script.setResultType(List.class);
    }

    @Override public AuthRateLimiter.Decision acquire(String key, AuthRateLimiter.Policy policy) {
        if (key == null || key.length() > 512) throw new IllegalArgumentException("Invalid limiter key");
        Objects.requireNonNull(policy);
        if (policy.refillPeriod().toMillis() < 1) throw new IllegalArgumentException("Shared refill must be at least one millisecond");
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(secret);
            String bucket = prefix + ":bucket:" + HexFormat.of().formatHex(mac.doFinal(key.getBytes(StandardCharsets.UTF_8)));
            List<?> result = redis.execute(script, List.of(bucket, prefix + ":keys"),
                    Integer.toString(policy.capacity()), Long.toString(policy.refillPeriod().toMillis()), Integer.toString(maximumKeys));
            if (result == null || result.size() != 2 || !(result.get(0) instanceof Number admitted)
                    || !(result.get(1) instanceof Number retry) || (admitted.longValue() != 0 && admitted.longValue() != 1))
                throw new IllegalStateException("Invalid admission result");
            return new AuthRateLimiter.Decision(admitted.longValue() == 1,
                    admitted.longValue() == 1 ? 0 : Math.max(1, retry.longValue()));
        } catch (Exception failure) {
            throw new DataAccessResourceFailureException("Authentication admission temporarily unavailable", failure);
        }
    }
}
