-- OFFLINE ONLY, after restore into an isolated database and before any web/mail worker starts.
-- This locks every restored password. Arrange fresh mailbox recovery before reopening traffic.
-- Also stop old binaries and clear ALL session stores; SQL cannot clear external session stores.
-- Supply -v restore_database=<isolated database> -v restore_confirmation=ISOLATED_RESTORE_OFFLINE.
\set ON_ERROR_STOP on
\if :{?restore_database}
\else
 DO $$ BEGIN RAISE EXCEPTION 'Missing restore_database; refusing restore reconciliation.'; END $$;
\endif
\if :{?restore_confirmation}
\else
 DO $$ BEGIN RAISE EXCEPTION 'Missing offline restore confirmation; refusing restore reconciliation.'; END $$;
\endif
SELECT current_database() = :'restore_database'
 AND :'restore_confirmation' = 'ISOLATED_RESTORE_OFFLINE' AS restore_guard \gset
\if :restore_guard
\else
 DO $$ BEGIN RAISE EXCEPTION 'Database or offline restore confirmation mismatch; refusing restore reconciliation.'; END $$;
\endif

BEGIN;
-- These locks serialize reconciliation; they do not replace isolating traffic and workers.
LOCK TABLE password_reset_control, campus_users, password_reset_requests,
 password_reset_tokens, email_verification_tokens, email_outbox IN ACCESS EXCLUSIVE MODE;
UPDATE password_reset_control
 SET enabled=FALSE, operator_intake_paused=TRUE, health_intake_paused=TRUE WHERE id=1;

-- Never move event ordering behind restored requests, account cutoffs or the sequence itself.
SELECT setval('auth_security_event_order', GREATEST(
 (SELECT last_value FROM auth_security_event_order),
 (SELECT COALESCE(MAX(request_order),0) FROM password_reset_requests),
 (SELECT COALESCE(MAX(reset_request_cutoff),0) FROM campus_users), 1), TRUE);
-- Not a BCrypt hash, so no password can authenticate until a fresh verified recovery completes.
UPDATE campus_users SET password_hash='!restore-recovery-required',
 credential_version=credential_version+1, reset_request_cutoff=nextval('auth_security_event_order');
UPDATE password_reset_tokens SET revoked_at=clock_timestamp()
 WHERE consumed_at IS NULL AND revoked_at IS NULL;
UPDATE email_verification_tokens SET revoked_at=clock_timestamp()
 WHERE consumed_at IS NULL AND revoked_at IS NULL;
UPDATE password_reset_requests SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,
 lease_owner=NULL,lease_expires_at=NULL,completed_at=COALESCE(completed_at,clock_timestamp());
UPDATE email_outbox SET status='CANCELLED',payload=NULL,key_id=NULL,nonce=NULL,
 lease_owner=NULL,lease_expires_at=NULL;
-- Preserve audit history and durable abuse counters. Do not enable recovery here.
COMMIT;
