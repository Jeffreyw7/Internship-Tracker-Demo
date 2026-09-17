CREATE TABLE campus_users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(80) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

CREATE TABLE applications (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES campus_users(id),
    company VARCHAR(100) NOT NULL,
    role VARCHAR(180) NOT NULL,
    location VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('SAVED','APPLIED','INTERVIEWING','OFFER','REJECTED','WITHDRAWN')),
    applied_date DATE,
    deadline DATE,
    follow_up_date DATE,
    notes VARCHAR(10000) NOT NULL,
    sample BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_app_owner_updated ON applications(owner_id, deleted, updated_at, id);
CREATE INDEX idx_app_owner_status ON applications(owner_id, deleted, status);
CREATE INDEX idx_app_owner_followup ON applications(owner_id, deleted, follow_up_date);
CREATE INDEX idx_app_owner_deadline ON applications(owner_id, deleted, deadline);

CREATE TABLE interview_stages (
    id UUID PRIMARY KEY,
    application_id UUID NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    name VARCHAR(120) NOT NULL,
    stage_date DATE,
    state VARCHAR(20) NOT NULL CHECK (state IN ('PLANNED','SCHEDULED','COMPLETED'))
);
CREATE INDEX idx_stages_application ON interview_stages(application_id, position);
