ALTER TABLE applications ADD COLUMN posting_url VARCHAR(2048) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN recruiter_name VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN recruiter_role VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN recruiter_email VARCHAR(254) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN recruiter_phone VARCHAR(50) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN recruiter_profile_url VARCHAR(2048) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN referral_channel VARCHAR(30) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE applications ADD COLUMN referral_source VARCHAR(200) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN referrer_name VARCHAR(120) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN referrer_contact VARCHAR(254) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN compensation_min NUMERIC(14,2);
ALTER TABLE applications ADD COLUMN compensation_max NUMERIC(14,2);
ALTER TABLE applications ADD COLUMN compensation_currency VARCHAR(3) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN compensation_period VARCHAR(20);
ALTER TABLE applications ADD COLUMN compensation_status VARCHAR(10) NOT NULL DEFAULT 'UNKNOWN';
ALTER TABLE applications ADD COLUMN compensation_original_text VARCHAR(1000) NOT NULL DEFAULT '';
ALTER TABLE applications ADD COLUMN posting_description_revision INTEGER NOT NULL DEFAULT 0;
ALTER TABLE applications ADD CONSTRAINT chk_posting_revision CHECK (posting_description_revision >= 0);
ALTER TABLE applications ADD CONSTRAINT chk_referral_channel CHECK (referral_channel IN ('UNKNOWN','JOB_BOARD','COMPANY_WEBSITE','CAREER_FAIR','EMPLOYEE','PROFESSOR','OTHER'));
ALTER TABLE applications ADD CONSTRAINT chk_compensation_status CHECK (compensation_status IN ('UNKNOWN','PAID','UNPAID'));
ALTER TABLE applications ADD CONSTRAINT chk_compensation_period CHECK (compensation_period IS NULL OR compensation_period IN ('HOUR','MONTH','YEAR','TOTAL_INTERNSHIP'));
ALTER TABLE applications ADD CONSTRAINT chk_compensation_range CHECK ((compensation_min IS NULL OR compensation_min >= 0) AND (compensation_max IS NULL OR compensation_max >= 0) AND (compensation_min IS NULL OR compensation_max IS NULL OR compensation_min <= compensation_max));
ALTER TABLE applications ADD CONSTRAINT chk_compensation_units CHECK ((compensation_min IS NULL AND compensation_max IS NULL) OR (compensation_status = 'PAID' AND compensation_currency <> '' AND compensation_period IS NOT NULL));

CREATE TABLE posting_description_snapshots (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    revision INTEGER NOT NULL CHECK (revision > 0),
    description_text TEXT NOT NULL,
    source_url VARCHAR(2048) NOT NULL,
    captured_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    capture_method VARCHAR(20) NOT NULL CHECK (capture_method = 'MANUAL'),
    UNIQUE (application_id, revision)
);
