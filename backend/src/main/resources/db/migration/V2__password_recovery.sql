ALTER TABLE campus_users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE campus_users ADD COLUMN password_changed_at TIMESTAMP(6) WITH TIME ZONE;
CREATE TABLE password_challenges (
    id UUID PRIMARY KEY, user_id UUID NOT NULL REFERENCES campus_users(id),
    purpose VARCHAR(6) NOT NULL CHECK (purpose IN ('RESET','CHANGE')),
    email VARCHAR(254) NOT NULL, token_hash VARCHAR(64) NOT NULL UNIQUE,
    credential_version BIGINT NOT NULL, initiator_binding_hash VARCHAR(64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP(6) WITH TIME ZONE, revoked_at TIMESTAMP(6) WITH TIME ZONE,
    CHECK ((purpose='RESET' AND initiator_binding_hash IS NULL) OR (purpose='CHANGE' AND initiator_binding_hash IS NOT NULL))
);
CREATE INDEX idx_password_user ON password_challenges(user_id,purpose,expires_at);
CREATE INDEX idx_password_expiry ON password_challenges(expires_at);
CREATE TABLE email_outbox (
    id UUID PRIMARY KEY, message_type VARCHAR(7) NOT NULL CHECK (message_type IN ('RESET','CHANGE','CHANGED')),
    password_challenge_id UUID REFERENCES password_challenges(id), user_id UUID REFERENCES campus_users(id),
    payload VARCHAR(4096), nonce VARCHAR(32), key_id VARCHAR(100),
    status VARCHAR(9) NOT NULL CHECK (status IN ('PENDING','SENDING','SENT','CANCELLED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0, next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, sent_at TIMESTAMP(6) WITH TIME ZONE,
    lease_owner UUID, lease_expires_at TIMESTAMP(6) WITH TIME ZONE, failure_category VARCHAR(40),
    CHECK ((message_type='CHANGED' AND password_challenge_id IS NULL AND user_id IS NOT NULL)
        OR (message_type IN ('RESET','CHANGE') AND password_challenge_id IS NOT NULL AND user_id IS NULL))
);
CREATE INDEX idx_password_mail_due ON email_outbox(status,next_attempt_at);
CREATE TABLE auth_rate_limits (
    scope VARCHAR(40) NOT NULL, subject_hash VARCHAR(64) NOT NULL,
    window_start TIMESTAMP(6) WITH TIME ZONE NOT NULL, attempts INTEGER NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY(scope,subject_hash,window_start)
);
