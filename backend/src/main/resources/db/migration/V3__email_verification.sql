ALTER TABLE campus_users ADD COLUMN email_verified_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE campus_users ADD COLUMN email_verification_exempt BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE campus_users SET email_verification_exempt = TRUE;

CREATE TABLE email_verification_tokens (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES campus_users(id),
    email VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP(6) WITH TIME ZONE,
    revoked_at TIMESTAMP(6) WITH TIME ZONE
);
CREATE INDEX idx_verification_user_expiry ON email_verification_tokens(user_id, expires_at);

CREATE TABLE email_outbox (
    id UUID PRIMARY KEY,
    verification_token_id UUID NOT NULL REFERENCES email_verification_tokens(id),
    payload VARCHAR(4096),
    key_id VARCHAR(100),
    nonce VARCHAR(32),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','SENDING','SENT','CANCELLED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    lease_owner UUID,
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP(6) WITH TIME ZONE,
    failure_category VARCHAR(40)
);
CREATE INDEX idx_email_outbox_due ON email_outbox(status, next_attempt_at);
CREATE INDEX idx_email_outbox_lease ON email_outbox(lease_expires_at);

CREATE TABLE auth_rate_limits (
    scope VARCHAR(40) NOT NULL,
    subject_hash VARCHAR(64) NOT NULL,
    window_start TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    attempts INTEGER NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    PRIMARY KEY (scope, subject_hash, window_start)
);
CREATE INDEX idx_auth_rate_limit_expiry ON auth_rate_limits(expires_at);
