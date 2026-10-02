package com.campus.tracker.auth;

import com.campus.tracker.common.ApiException;
import com.campus.tracker.mail.MailProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HexFormat;

/** Each counter update commits independently, including rejected password attempts. */
@Service
public class AuthRateLimiter {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MailProperties config;
    public AuthRateLimiter(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock, MailProperties config) {
        this.jdbc=jdbc; this.clock=clock; this.config=config;
        tx=new TransactionTemplate(manager); tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void requireRequestAllowed(String scope, String address) {
        boolean allowed = allowed(scope+"-minute", address, config.getRequestMinute(), 60)
                & allowed(scope+"-hour", address, config.getRequestHour(), 3600);
        if (!allowed) throw limited();
    }
    public boolean reserveEmail(String email) {
        return allowed("mail-minute",email,config.getEmailMinute(),60)
                & allowed("mail-hour",email,config.getEmailHour(),3600)
                & allowed("mail-day",email,config.getEmailDay(),86400);
    }
    /** Reserve only for an eligible delivery, after account and recipient checks. */
    public boolean reserveDelivery() {
        return allowed("mail-global", "all",config.getMailDay(),86400);
    }
    public void requireConfirmationAllowed(String token, String address) {
        boolean allowed=allowed("confirm-ip",address,config.getConfirmMinute(),60)
                & allowed("confirm-token",token,config.getConfirmToken(),900);
        if(!allowed) throw limited();
    }
    private ApiException limited() { return new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many requests. Wait before trying again.",60); }
    private boolean allowed(String scope, String subject, int maximum, long seconds) {
        var start=java.time.Instant.ofEpochSecond(clock.instant().getEpochSecond()/seconds*seconds);
        String hash=hmac(subject);
        // An insert conflict is rolled back in its own transaction before retrying the update (PostgreSQL-safe).
        try {
            tx.executeWithoutResult(s -> jdbc.update("INSERT INTO auth_rate_limits(scope,subject_hash,window_start,attempts,expires_at) VALUES(?,?,?,0,?)",
                    scope,hash,Timestamp.from(start),Timestamp.from(start.plusSeconds(seconds+60))));
        } catch (DuplicateKeyException ignored) { /* Another request has already created this bucket. */ }
        return Boolean.TRUE.equals(tx.execute(s -> jdbc.update("UPDATE auth_rate_limits SET attempts=attempts+1 WHERE scope=? AND subject_hash=? AND window_start=? AND attempts<?",
                scope,hash,Timestamp.from(start),maximum)==1));
    }
    private String hmac(String subject) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(config.getRateLimitSecret().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(subject.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("Rate limit unavailable",e); }
    }
}
