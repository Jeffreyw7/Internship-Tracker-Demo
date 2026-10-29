ALTER TABLE campus_users ADD COLUMN credential_version BIGINT NOT NULL DEFAULT 0 CHECK (credential_version >= 0);
ALTER TABLE campus_users ADD COLUMN reset_request_cutoff BIGINT NOT NULL DEFAULT 0 CHECK (reset_request_cutoff >= 0);
CREATE SEQUENCE auth_security_event_order AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE CACHE 1;
CREATE TABLE password_reset_control (
    id INTEGER PRIMARY KEY CHECK (id=1), enabled BOOLEAN NOT NULL DEFAULT FALSE,
    operator_intake_paused BOOLEAN NOT NULL DEFAULT FALSE, health_intake_paused BOOLEAN NOT NULL DEFAULT FALSE
);
INSERT INTO password_reset_control(id) VALUES(1);
CREATE TABLE password_reset_requests (
    id UUID PRIMARY KEY, request_order BIGINT NOT NULL UNIQUE CHECK (request_order > 0),
    payload VARCHAR(4096), key_id VARCHAR(100), nonce VARCHAR(32),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PROCESSING','DONE','CANCELLED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0 CHECK (attempts >= 0 AND attempts <= 5),
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, lease_owner UUID, lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE, failure_category VARCHAR(40),
    CHECK (expires_at > created_at),
    CHECK ((status IN ('PENDING','PROCESSING') AND payload IS NOT NULL AND key_id IS NOT NULL AND nonce IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('DONE','CANCELLED','FAILED') AND payload IS NULL AND key_id IS NULL AND nonce IS NULL AND completed_at IS NOT NULL)),
    CHECK ((status='PROCESSING' AND lease_owner IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (status<>'PROCESSING' AND lease_owner IS NULL AND lease_expires_at IS NULL))
);
CREATE INDEX idx_reset_intake_due ON password_reset_requests(status,next_attempt_at);
CREATE INDEX idx_reset_intake_lease ON password_reset_requests(status,lease_expires_at);
CREATE INDEX idx_reset_intake_completed ON password_reset_requests(completed_at);
CREATE TABLE password_reset_tokens (
    id UUID PRIMARY KEY, request_id UUID NOT NULL UNIQUE REFERENCES password_reset_requests(id),
    user_id UUID NOT NULL REFERENCES campus_users(id), email VARCHAR(254) NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE, credential_version BIGINT NOT NULL CHECK (credential_version >= 0),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    consumed_at TIMESTAMP(6) WITH TIME ZONE, revoked_at TIMESTAMP(6) WITH TIME ZONE,
    CHECK (expires_at > created_at), CHECK (consumed_at IS NULL OR revoked_at IS NULL)
);
CREATE INDEX idx_reset_token_user_expiry ON password_reset_tokens(user_id,expires_at);
CREATE INDEX idx_reset_token_expiry ON password_reset_tokens(expires_at);
CREATE TABLE auth_security_events (
    id UUID PRIMARY KEY, completion_id UUID NOT NULL UNIQUE,
    event_type VARCHAR(40) NOT NULL CHECK (event_type='PASSWORD_RESET_COMPLETED'),
    occurred_at TIMESTAMP(6) WITH TIME ZONE NOT NULL, account_subject VARCHAR(100) NOT NULL, correlation_id UUID NOT NULL
);
CREATE INDEX idx_security_event_time ON auth_security_events(occurred_at);
ALTER TABLE email_outbox ALTER COLUMN verification_token_id DROP NOT NULL;
ALTER TABLE email_outbox ADD COLUMN message_type VARCHAR(32) NOT NULL DEFAULT 'EMAIL_VERIFICATION';
ALTER TABLE email_outbox ADD COLUMN password_reset_token_id UUID REFERENCES password_reset_tokens(id);
ALTER TABLE email_outbox ADD CONSTRAINT chk_outbox_purpose CHECK (
    (message_type='EMAIL_VERIFICATION' AND verification_token_id IS NOT NULL AND password_reset_token_id IS NULL)
    OR (message_type IN ('PASSWORD_RESET_LINK','PASSWORD_CHANGED') AND verification_token_id IS NULL AND password_reset_token_id IS NOT NULL)
);
ALTER TABLE email_outbox ADD CONSTRAINT uniq_reset_mail UNIQUE(message_type,password_reset_token_id);
CREATE INDEX idx_outbox_reset_token ON email_outbox(password_reset_token_id);
