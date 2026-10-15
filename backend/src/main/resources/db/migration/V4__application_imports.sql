CREATE TABLE application_imports (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES campus_users(id),
    state VARCHAR(12) NOT NULL CHECK (state IN ('PREVIEW','COMPLETED','SUPERSEDED')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP(6) WITH TIME ZONE,
    receipt_retain_until TIMESTAMP(6) WITH TIME ZONE,
    schema_version INTEGER NOT NULL,
    policy_version VARCHAR(80) NOT NULL,
    source_digest VARCHAR(64) NOT NULL,
    review_digest VARCHAR(64) NOT NULL,
    canonical_payload TEXT,
    payload_bytes BIGINT NOT NULL DEFAULT 0 CHECK (payload_bytes >= 0 AND payload_bytes <= 8388608),
    receipt TEXT,
    receipt_bytes BIGINT NOT NULL DEFAULT 0 CHECK (receipt_bytes >= 0 AND receipt_bytes <= 131072),
    successor_id UUID,
    CHECK (
      (state='PREVIEW' AND canonical_payload IS NOT NULL AND payload_bytes>0 AND receipt IS NULL AND receipt_bytes=0
        AND completed_at IS NULL AND receipt_retain_until IS NULL AND successor_id IS NULL)
      OR (state='COMPLETED' AND canonical_payload IS NULL AND payload_bytes=0 AND receipt IS NOT NULL AND receipt_bytes>0
        AND completed_at IS NOT NULL AND receipt_retain_until IS NOT NULL AND successor_id IS NULL)
      OR (state='SUPERSEDED' AND canonical_payload IS NULL AND payload_bytes=0 AND receipt IS NULL AND receipt_bytes=0
        AND completed_at IS NULL AND receipt_retain_until IS NULL AND successor_id IS NOT NULL)
    )
);
CREATE INDEX idx_import_owner_created ON application_imports(owner_id, created_at);
CREATE INDEX idx_import_state_expires ON application_imports(state, expires_at);
CREATE INDEX idx_import_state_retain ON application_imports(state, receipt_retain_until);

CREATE TABLE application_import_quota_locks (
    scope_key VARCHAR(80) PRIMARY KEY
);
INSERT INTO application_import_quota_locks(scope_key) VALUES ('GLOBAL');

CREATE TABLE application_import_rates (
    scope_key VARCHAR(100) PRIMARY KEY,
    window_started TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    request_count INTEGER NOT NULL CHECK (request_count >= 0)
);
