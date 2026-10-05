CREATE TABLE export_jobs (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES campus_users(id),
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','RUNNING','READY','FAILED','CANCELLED','EXPIRED')),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    cutoff_at TIMESTAMP(6) WITH TIME ZONE,
    cutoff_id UUID,
    lease_owner UUID,
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    attempts INTEGER NOT NULL DEFAULT 0,
    object_key VARCHAR(200),
    byte_count BIGINT,
    failure_code VARCHAR(40)
);
CREATE INDEX idx_export_owner_state ON export_jobs(owner_id,state,expires_at);
CREATE INDEX idx_export_claim ON export_jobs(state,created_at,id);
CREATE INDEX idx_export_expiry ON export_jobs(expires_at,id);
-- Register publication intent before upload so a crash cannot leave untracked private objects.
CREATE TABLE export_objects (
    object_key VARCHAR(200) PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES export_jobs(id),
    delete_after TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_export_objects_cleanup ON export_objects(delete_after,job_id);
