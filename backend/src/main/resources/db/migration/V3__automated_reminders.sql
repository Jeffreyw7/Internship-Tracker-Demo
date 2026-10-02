CREATE TABLE reminder_preferences (
    owner_id UUID PRIMARY KEY REFERENCES campus_users(id),
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    interviews_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    deadlines_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    follow_ups_enabled BOOLEAN NOT NULL DEFAULT TRUE,
    interview_lead_days INTEGER NOT NULL DEFAULT 1 CHECK (interview_lead_days BETWEEN 0 AND 30),
    deadline_lead_days INTEGER NOT NULL DEFAULT 3 CHECK (deadline_lead_days BETWEEN 0 AND 30),
    follow_up_lead_days INTEGER NOT NULL DEFAULT 0 CHECK (follow_up_lead_days BETWEEN 0 AND 30),
    timezone VARCHAR(100) NOT NULL DEFAULT 'UTC',
    reminder_time VARCHAR(5) NOT NULL DEFAULT '09:00',
    delivery_mode VARCHAR(12) NOT NULL DEFAULT 'IMMEDIATE' CHECK (delivery_mode IN ('IMMEDIATE','DAILY_DIGEST')),
    digest_time VARCHAR(5) NOT NULL DEFAULT '09:00',
    email_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    last_digest_date DATE,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE reminder_deliveries (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES campus_users(id),
    delivery_mode VARCHAR(12) NOT NULL,
    status VARCHAR(9) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','SENDING','SENT','CANCELLED','FAILED')),
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    sent_at TIMESTAMP(6) WITH TIME ZONE,
    lease_owner UUID,
    lease_expires_at TIMESTAMP(6) WITH TIME ZONE,
    failure_category VARCHAR(40)
);
CREATE INDEX idx_reminder_delivery_due ON reminder_deliveries(status,next_attempt_at);
CREATE TABLE reminders (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES campus_users(id),
    application_id UUID NOT NULL REFERENCES applications(id),
    source_id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('INTERVIEW','DEADLINE','FOLLOW_UP')),
    event_date DATE NOT NULL,
    company VARCHAR(100) NOT NULL,
    role VARCHAR(180) NOT NULL,
    label VARCHAR(120) NOT NULL,
    due_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    state VARCHAR(9) NOT NULL DEFAULT 'PENDING' CHECK (state IN ('PENDING','READY','DISMISSED','CANCELLED')),
    snoozed BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    delivery_id UUID REFERENCES reminder_deliveries(id),
    email_sent_at TIMESTAMP(6) WITH TIME ZONE,
    UNIQUE(owner_id,kind,source_id,event_date)
);
CREATE INDEX idx_reminder_owner_due ON reminders(owner_id,state,due_at);
CREATE INDEX idx_reminder_job ON reminders(delivery_id);
