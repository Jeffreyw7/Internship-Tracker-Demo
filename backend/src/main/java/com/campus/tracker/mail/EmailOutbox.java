package com.campus.tracker.mail;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EmailOutbox {
    private final JdbcTemplate jdbc;
    private final OutboxCrypto crypto;
    private final Clock clock;

    public EmailOutbox(JdbcTemplate jdbc, OutboxCrypto crypto, Clock clock) {
        this.jdbc = jdbc; this.crypto = crypto; this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID tokenId, String email, String token) {
        UUID id = UUID.randomUUID();
        var payload = crypto.encrypt(id, email, token);
        var now = clock.instant().atOffset(ZoneOffset.UTC);
        jdbc.update("""
                INSERT INTO email_outbox(id,verification_token_id,payload,key_id,nonce,status,attempts,next_attempt_at,created_at)
                VALUES(?,?,?,?,?,'PENDING',0,?,?)
                """, id, tokenId, payload.payload(), payload.keyId(), payload.nonce(), now, now);
    }
}
