package com.campus.tracker.mail;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailOutbox {
    private final JdbcTemplate jdbc;
    private final OutboxCrypto crypto;
    private final Clock clock;
    private boolean primaryTime;

    public EmailOutbox(JdbcTemplate jdbc, OutboxCrypto crypto, Clock clock) {
        this.jdbc = jdbc; this.crypto = crypto; this.clock = clock;
    }
    @Autowired
    public EmailOutbox(JdbcTemplate jdbc, OutboxCrypto crypto, Clock clock, Environment environment) {
        this(jdbc, crypto, clock); primaryTime = environment.acceptsProfiles(Profiles.of("prod"));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID tokenId, String email, String token) {
        enqueue(tokenId, EmailMessage.Type.EMAIL_VERIFICATION, email, token, null);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueueReset(UUID tokenId, String email, String token) {
        enqueue(tokenId, EmailMessage.Type.PASSWORD_RESET_LINK, email, token, null);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueuePasswordChanged(UUID tokenId, String email, Instant occurredAt) {
        enqueue(tokenId, EmailMessage.Type.PASSWORD_CHANGED, email, null, occurredAt);
    }
    private void enqueue(UUID tokenId, EmailMessage.Type type, String email, String token, Instant occurredAt) {
        UUID id = UUID.randomUUID();
        var payload = crypto.encrypt(id, type, email, token, occurredAt);
        Instant instant = primaryTime ? jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant()) : clock.instant();
        var now = instant.atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO email_outbox(id,message_type,verification_token_id,password_reset_token_id,payload,key_id,nonce,status,attempts,next_attempt_at,created_at)
                VALUES(?,?,?,?,?,?,?,'PENDING',0,?,?)
                """, id, type.name(), type == EmailMessage.Type.EMAIL_VERIFICATION ? tokenId : null,
                type == EmailMessage.Type.EMAIL_VERIFICATION ? null : tokenId, payload.payload(), payload.keyId(), payload.nonce(), now, now);
    }
}
