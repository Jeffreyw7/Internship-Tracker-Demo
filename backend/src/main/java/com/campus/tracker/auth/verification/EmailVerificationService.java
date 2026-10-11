package com.campus.tracker.auth.verification;

import com.campus.tracker.auth.AuthDtos;
import com.campus.tracker.auth.CampusUser;
import com.campus.tracker.auth.UserRepository;
import com.campus.tracker.common.ApiException;
import com.campus.tracker.mail.EmailOutbox;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Service
public class EmailVerificationService {
    private static final String INVALID_MESSAGE = "This verification request is invalid or expired. Request a new email and try again.";
    private final UserRepository users;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final EmailOutbox outbox;
    private final AuthRateLimiter rateLimiter;
    private final Clock clock;
    private final Duration tokenTtl;
    private final SecureRandom random = new SecureRandom();
    private final String dummyPasswordHash;

    public EmailVerificationService(UserRepository users, JdbcTemplate jdbc, PasswordEncoder passwords,
            EmailOutbox outbox, AuthRateLimiter rateLimiter, Clock clock,
            @Value("${campus.verification.token-ttl:24h}") Duration tokenTtl) {
        this.users = users; this.jdbc = jdbc; this.passwords = passwords; this.outbox = outbox;
        this.rateLimiter = rateLimiter; this.clock = clock; this.tokenTtl = tokenTtl;
        this.dummyPasswordHash = passwords.encode("verification-timing-placeholder");
    }

    @Transactional
    public void issue(CampusUser user) {
        if (user.canAuthenticate() || !rateLimiter.reserveEmail(user.getEmail())) return;
        Instant now = micros(clock.instant());
        UUID tokenId = UUID.randomUUID();
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbc.update("INSERT INTO email_verification_tokens(id,user_id,email,token_hash,created_at,expires_at) VALUES (?,?,?,?,?,?)",
                tokenId, user.getId(), user.getEmail(), hash(token), Timestamp.from(now), Timestamp.from(now.plus(tokenTtl)));
        outbox.enqueue(tokenId, user.getEmail(), token);
    }

    @Transactional
    public AuthDtos.Accepted resend(String submittedEmail) {
        String email = submittedEmail.trim().toLowerCase(Locale.ROOT);
        Optional<UUID> candidateId = users.findByEmail(email).map(CampusUser::getId);
        passwords.matches("verification-timing-placeholder", dummyPasswordHash);
        candidateId.flatMap(users::findByIdForUpdate).filter(user -> !user.canAuthenticate()).ifPresent(this::issue);
        return accepted();
    }

    @Transactional
    public AuthDtos.Accepted confirm(String token, String password, String remoteAddress) {
        rateLimiter.requireConfirmationAllowed(token, remoteAddress);
        Challenge first = findChallenge(token, false).orElseThrow(this::invalid);
        CampusUser user = users.findByIdForUpdate(first.userId()).orElseThrow(this::invalid);
        Challenge challenge = findChallenge(token, true).orElseThrow(this::invalid);
        Instant now = clock.instant();
        boolean valid = challenge.userId().equals(user.getId()) && challenge.email().equals(user.getEmail())
                && challenge.consumedAt() == null && challenge.revokedAt() == null
                && challenge.expiresAt().isAfter(now) && !user.canAuthenticate();
        if (!valid || !passwords.matches(password, user.getPasswordHash())) throw invalid();
        Instant verifiedAt = micros(now);
        user.verifyEmail(verifiedAt);
        jdbc.update("UPDATE email_verification_tokens SET consumed_at=? WHERE id=?", Timestamp.from(verifiedAt), challenge.id());
        jdbc.update("UPDATE email_verification_tokens SET revoked_at=? WHERE user_id=? AND id<>? AND consumed_at IS NULL AND revoked_at IS NULL",
                Timestamp.from(verifiedAt), user.getId(), challenge.id());
        jdbc.update("UPDATE email_outbox SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,lease_owner=NULL,lease_expires_at=NULL,next_attempt_at=? "
                        + "WHERE message_type='EMAIL_VERIFICATION' AND verification_token_id IN (SELECT id FROM email_verification_tokens WHERE user_id=?) "
                        + "AND status IN ('PENDING','SENDING')",
                Timestamp.from(verifiedAt), user.getId());
        return new AuthDtos.Accepted("EMAIL_VERIFIED", "Your email is verified. Sign in to continue.");
    }

    private Optional<Challenge> findChallenge(String token, boolean lock) {
        List<Challenge> rows = jdbc.query("SELECT id,user_id,email,expires_at,consumed_at,revoked_at "
                        + "FROM email_verification_tokens WHERE token_hash=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> challenge(rs), hash(token));
        return rows.stream().findFirst();
    }

    private Challenge challenge(ResultSet rs) throws SQLException {
        return new Challenge(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("email"),
                rs.getTimestamp("expires_at").toInstant(), instant(rs, "consumed_at"), instant(rs, "revoked_at"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column); return timestamp == null ? null : timestamp.toInstant();
    }

    private String hash(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private ApiException invalid() { return new ApiException(HttpStatus.BAD_REQUEST, "VERIFICATION_INVALID", INVALID_MESSAGE); }
    private AuthDtos.Accepted accepted() { return new AuthDtos.Accepted("VERIFICATION_REQUEST_ACCEPTED",
            "If this address can receive verification mail, a message will arrive shortly."); }
    private Instant micros(Instant value) { return value.truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    private record Challenge(UUID id, UUID userId, String email, Instant expiresAt, Instant consumedAt, Instant revokedAt) {}
}
