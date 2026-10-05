-- V5 is already published; evolve export processing through an additive migration.
ALTER TABLE export_jobs ADD COLUMN next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_export_due ON export_jobs(state,next_attempt_at,created_at,id);
CREATE INDEX idx_app_owner_created ON applications(owner_id,created_at,id);
