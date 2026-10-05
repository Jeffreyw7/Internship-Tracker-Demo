-- PostgreSQL qualification confirms V1's owner/updated/id index serves the default DESC keyset order by scanning backward.
-- Keep read index additions gated by measured plans rather than duplicate that index.
-- Bounded mail maintenance must find expired tokens and their outbox references without table scans.
CREATE INDEX idx_verification_expiry ON email_verification_tokens(expires_at, id);
CREATE INDEX idx_outbox_verification_token ON email_outbox(verification_token_id);
