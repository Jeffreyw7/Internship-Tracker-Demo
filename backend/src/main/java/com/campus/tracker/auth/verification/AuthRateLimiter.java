package com.campus.tracker.auth.verification;

import com.campus.tracker.auth.abuse.AuthThrottleException;
import com.campus.tracker.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;

/** Counter statements commit independently; no bucket lock crosses an account transaction. */
@Service
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final List<byte[]> secrets;
    private final boolean enabled;
    private boolean primaryTime;

    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock, String secret, boolean enabled) {
        this(jdbc, clock, secret, enabled, List.of());
    }
    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock, String secret, boolean enabled, List<String> previousSecrets) {
        var keys = new ArrayList<String>(); keys.add(secret);
        if (previousSecrets != null) previousSecrets.stream().filter(value -> !value.isBlank()).forEach(keys::add);
        if (keys.stream().anyMatch(value -> value.length() < 24)) throw new IllegalArgumentException("Rate-limit secrets must be at least 24 characters.");
        this.jdbc = jdbc; this.clock = clock; this.enabled = enabled;
        this.secrets = keys.stream().distinct().map(value -> value.getBytes(StandardCharsets.UTF_8)).toList();
    }
    @Autowired
    public AuthRateLimiter(JdbcTemplate jdbc, Clock clock,
            @Value("${campus.verification.rate-limit-secret}") String secret,
            @Value("${campus.verification.rate-limits-enabled:true}") boolean enabled,
            @Value("${campus.verification.previous-rate-limit-secrets:}") List<String> previousSecrets, Environment environment) {
        this(jdbc, clock, secret, enabled, previousSecrets);
        this.primaryTime = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requireRequestAllowed(String ip) {
        if (!enabled) return;
        Instant now = now();
        boolean admitted = allowed("request-minute", ip, 5, Duration.ofMinutes(1), now)
                & allowed("request-hour", ip, 30, Duration.ofHours(1), now);
        if (!admitted) throw verificationLimited();
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reserveEmail(String email) {
        Instant now = now();
        boolean recipient = !enabled || (allowed("email-minute", email, 1, Duration.ofMinutes(1), now)
                & allowed("email-hour", email, 5, Duration.ofHours(1), now)
                & allowed("email-day", email, 10, Duration.ofDays(1), now));
        return recipient && allowed("mail-global-day", "all", 1000, Duration.ofDays(1), now);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requireConfirmationAllowed(String token, String ip) {
        if (!enabled) return;
        Instant now = now();
        boolean admitted = allowed("confirm-ip-minute", ip, 10, Duration.ofMinutes(1), now)
                & allowed("confirm-token-15m", token, 10, Duration.ofMinutes(15), now);
        if (!admitted) throw verificationLimited();
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requireResetRequestAllowed(String ip) {
        Instant now = now();
        require("reset-global-minute", "all", 100, Duration.ofMinutes(1), now);
        require("reset-global-day", "all", 10000, Duration.ofDays(1), now);
        require("reset-ip-minute", ip, 5, Duration.ofMinutes(1), now);
        require("reset-ip-hour", ip, 30, Duration.ofHours(1), now);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reserveResetAddress(String email) {
        Instant now = now();
        return allowed("reset-email-minute", email, 1, Duration.ofMinutes(1), now)
                & allowed("reset-email-hour", email, 5, Duration.ofHours(1), now)
                & allowed("reset-email-day", email, 10, Duration.ofDays(1), now);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void requireResetConfirmationAllowed(String token, String ip) {
        Instant now = now();
        require("reset-confirm-global-minute", "all", 60, Duration.ofMinutes(1), now);
        require("reset-confirm-ip-minute", ip, 10, Duration.ofMinutes(1), now);
        require("reset-confirm-token-15m", token, 10, Duration.ofMinutes(15), now);
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reserveLinkMail() { return allowed("mail-global-day", "all", 1000, Duration.ofDays(1), now()); }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public boolean reserveNoticeMail() { return allowed("reset-notice-day", "all", 1000, Duration.ofDays(1), now()); }
    public String subjectHash(String value) { return hmac(secrets.get(0), value == null ? "unknown" : value); }

    private void require(String scope, String subject, int maximum, Duration window, Instant now) {
        if (!allowed(scope, subject, maximum, window, now)) {
            long seconds = window.toSeconds();
            throw new AuthThrottleException(seconds - Math.floorMod(now.getEpochSecond(), seconds));
        }
    }
    private ApiException verificationLimited() {
        return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests. Wait before trying again.");
    }
    private Instant now() {
        return primaryTime ? jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant()) : clock.instant();
    }
    private boolean allowed(String scope, String rawSubject, int maximum, Duration window, Instant now) {
        boolean result = true;
        for (byte[] secret : secrets) result &= reserve(scope, hmac(secret, rawSubject == null ? "unknown" : rawSubject), maximum, window, now);
        return result;
    }
    private boolean reserve(String scope, String subject, int maximum, Duration window, Instant now) {
        long seconds = window.toSeconds();
        Instant start = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), seconds) * seconds);
        String increment = "UPDATE auth_rate_limits SET attempts=attempts+1 WHERE scope=? AND subject_hash=? AND window_start=? AND attempts<?";
        if (jdbc.update(increment, scope, subject, Timestamp.from(start), maximum) == 1) return true;
        Integer count = jdbc.query("SELECT attempts FROM auth_rate_limits WHERE scope=? AND subject_hash=? AND window_start=?",
                rs -> rs.next() ? rs.getInt(1) : null, scope, subject, Timestamp.from(start));
        if (count != null) return count < maximum && jdbc.update(increment, scope, subject, Timestamp.from(start), maximum) == 1;
        try {
            jdbc.update("INSERT INTO auth_rate_limits(scope,subject_hash,window_start,attempts,expires_at) VALUES (?,?,?,?,?)",
                    scope, subject, Timestamp.from(start), 1, Timestamp.from(start.plus(window).plusSeconds(60)));
            return true;
        } catch (DuplicateKeyException concurrentInsert) {
            return jdbc.update(increment, scope, subject, Timestamp.from(start), maximum) == 1;
        }
    }
    private String hmac(byte[] secret, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) { throw new IllegalStateException("Cannot protect rate-limit identifiers."); }
    }
}
