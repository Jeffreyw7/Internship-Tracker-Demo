package com.campus.tracker.auth.verification;

import com.campus.tracker.common.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

@Service
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final byte[] secret;
    private final boolean enabled;

    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock,
            @Value("${campus.verification.rate-limit-secret}") String secret,
            @Value("${campus.verification.rate-limits-enabled:true}") boolean enabled) {
        if (secret.length() < 24) throw new IllegalArgumentException("Verification rate-limit secret must be at least 24 characters.");
        this.jdbc = jdbc; this.clock = clock; this.secret = secret.getBytes(StandardCharsets.UTF_8); this.enabled = enabled;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireRequestAllowed(String remoteAddress) {
        if (!enabled) return;
        boolean allowed = allowed("request-minute", remoteAddress, 5, Duration.ofMinutes(1))
                & allowed("request-hour", remoteAddress, 30, Duration.ofHours(1));
        if (!allowed) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many requests. Wait before trying again.");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reserveEmail(String email) {
        if (!enabled) return true;
        return allowed("email-minute", email, 1, Duration.ofMinutes(1))
                & allowed("email-hour", email, 5, Duration.ofHours(1))
                & allowed("email-day", email, 10, Duration.ofDays(1))
                & allowed("mail-global-day", "all", 1000, Duration.ofDays(1));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void requireConfirmationAllowed(String token, String remoteAddress) {
        if (!enabled) return;
        boolean allowed = allowed("confirm-ip-minute", remoteAddress, 10, Duration.ofMinutes(1))
                & allowed("confirm-token-15m", token, 10, Duration.ofMinutes(15));
        if (!allowed) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                "Too many verification attempts. Wait before trying again.");
    }

    // Existing buckets are incremented atomically by the database; insertion is synchronized for this process.
    private synchronized boolean allowed(String scope, String rawSubject, int maximum, Duration window) {
        Instant now = clock.instant();
        long seconds = window.toSeconds();
        Instant start = Instant.ofEpochSecond((now.getEpochSecond() / seconds) * seconds);
        String subject = hmac(rawSubject == null ? "unknown" : rawSubject);
        int updated = jdbc.update("UPDATE auth_rate_limits SET attempts=attempts+1 "
                        + "WHERE scope=? AND subject_hash=? AND window_start=? AND attempts<?",
                scope, subject, Timestamp.from(start), maximum);
        if (updated == 1) return true;
        Integer count = jdbc.query("SELECT attempts FROM auth_rate_limits WHERE scope=? AND subject_hash=? AND window_start=?",
                rs -> rs.next() ? rs.getInt(1) : null, scope, subject, Timestamp.from(start));
        if (count != null) return false;
        jdbc.update("INSERT INTO auth_rate_limits(scope,subject_hash,window_start,attempts,expires_at) VALUES (?,?,?,?,?)",
                scope, subject, Timestamp.from(start), 1, Timestamp.from(start.plus(window).plus(Duration.ofMinutes(1))));
        return true;
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot protect rate-limit identifiers.", e);
        }
    }
}
