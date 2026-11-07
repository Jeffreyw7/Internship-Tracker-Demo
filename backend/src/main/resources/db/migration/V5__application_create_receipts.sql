-- Keep create intents permanently so a delayed retry cannot duplicate a committed or cancelled save.
CREATE TABLE application_create_receipts (
    owner_id UUID NOT NULL REFERENCES campus_users(id),
    idempotency_key UUID NOT NULL,
    payload_fingerprint VARCHAR(64),
    application_id UUID REFERENCES applications(id),
    PRIMARY KEY (owner_id, idempotency_key),
    CHECK ((application_id IS NULL AND payload_fingerprint IS NULL)
        OR (application_id IS NOT NULL AND payload_fingerprint IS NOT NULL))
);
