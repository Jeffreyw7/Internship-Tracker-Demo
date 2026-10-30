-- Private PostgreSQL operator report; never expose through public /api/health.
-- Run with an operator/monitor identity that can SELECT the referenced tables:
-- psql -X -v ON_ERROR_STOP=1 -f deploy/monitoring/password-reset-health.sql
-- Optional: -v audit_exported_through='2026-10-29T12:00:00Z'
-- Supply that timestamp ONLY after a durable restricted sink confirms every event
-- through that instant, including timestamp ties. Export at least once, deduplicate
-- by event id, and persist the sink checkpoint. This report is NOT an exporter.
-- Missing checkpoint is an explicit release gap, never a zero-lag success.
-- Poll privately every 15s; metric names are fixed and no identity/payload is output.
-- Alert: intake >30s, eligible link >120s, any deadline-near/failed notice,
-- crypto failure or expired work; page on missing control/checkpoint or health pause.
-- Pick an export-lag budget well below the independent 30-day retention deadline.
-- Request latency/5xx, permits, DB timeouts and worker/cleanup heartbeats require
-- separate application/edge instrumentation; table state cannot prove those healthy.
\if :{?audit_exported_through}
\else
\set audit_exported_through ''
\endif
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '2s';
SET LOCAL lock_timeout = '1s';
WITH stamp AS (
    SELECT CURRENT_TIMESTAMP AS now,
           NULLIF(:'audit_exported_through', '')::timestamptz AS exported_through
), intake AS (
    SELECT created_at, expires_at, status, lease_expires_at
    FROM password_reset_requests WHERE status IN ('PENDING', 'PROCESSING')
), links AS (
    SELECT o.created_at, t.expires_at FROM email_outbox o
    JOIN password_reset_tokens t ON t.id = o.password_reset_token_id
    JOIN campus_users u ON u.id = t.user_id CROSS JOIN stamp s
    WHERE o.message_type = 'PASSWORD_RESET_LINK' AND o.status IN ('PENDING', 'SENDING')
      AND t.expires_at > s.now AND t.consumed_at IS NULL AND t.revoked_at IS NULL
      AND t.credential_version = u.credential_version AND t.email = u.email
), notices AS (
    SELECT created_at FROM email_outbox
    WHERE message_type = 'PASSWORD_CHANGED' AND status IN ('PENDING', 'SENDING')
), metrics AS (
    SELECT 'control_row_missing' AS metric, (1 - COUNT(*))::numeric AS value
      FROM password_reset_control WHERE id = 1
    UNION ALL SELECT 'recovery_enabled', enabled::int FROM password_reset_control WHERE id = 1
    UNION ALL SELECT 'operator_intake_paused', operator_intake_paused::int FROM password_reset_control WHERE id = 1
    UNION ALL SELECT 'health_intake_paused', health_intake_paused::int FROM password_reset_control WHERE id = 1
    UNION ALL SELECT 'intake_pending', COUNT(*) FROM intake
    UNION ALL SELECT 'intake_oldest_seconds', COALESCE(EXTRACT(EPOCH FROM (s.now - MIN(i.created_at))), 0)
      FROM stamp s LEFT JOIN intake i ON TRUE GROUP BY s.now
    UNION ALL SELECT 'intake_deadline_within_60s', COUNT(*) FROM intake, stamp WHERE expires_at <= now + INTERVAL '60 seconds'
    UNION ALL SELECT 'intake_expired_pending', COUNT(*) FROM intake, stamp WHERE expires_at <= now
    UNION ALL SELECT 'eligible_link_pending', COUNT(*) FROM links
    UNION ALL SELECT 'eligible_link_oldest_seconds', COALESCE(EXTRACT(EPOCH FROM (s.now - MIN(l.created_at))), 0)
      FROM stamp s LEFT JOIN links l ON TRUE GROUP BY s.now
    UNION ALL SELECT 'eligible_link_deadline_within_120s', COUNT(*) FROM links, stamp WHERE expires_at <= now + INTERVAL '120 seconds'
    UNION ALL SELECT 'notice_pending', COUNT(*) FROM notices
    UNION ALL SELECT 'notice_deadline_within_1h', COUNT(*) FROM notices, stamp WHERE created_at <= now - INTERVAL '23 hours'
    UNION ALL SELECT 'notice_failed_retained', COUNT(*) FROM email_outbox WHERE message_type = 'PASSWORD_CHANGED' AND status = 'FAILED'
    UNION ALL SELECT 'crypto_failure_retained', COUNT(*) FROM (
        SELECT failure_category FROM password_reset_requests WHERE failure_category = 'PAYLOAD_INVALID'
        UNION ALL SELECT failure_category FROM email_outbox WHERE failure_category = 'PAYLOAD_INVALID'
    ) failures
    UNION ALL SELECT 'intake_expired_lease', COUNT(*) FROM intake, stamp WHERE status = 'PROCESSING' AND lease_expires_at < now
    UNION ALL SELECT 'mail_expired_lease', COUNT(*) FROM email_outbox, stamp
      WHERE message_type IN ('PASSWORD_RESET_LINK', 'PASSWORD_CHANGED') AND status = 'SENDING' AND lease_expires_at < now
    UNION ALL SELECT 'terminal_mail_cleanup_due', COUNT(*) FROM email_outbox, stamp
      WHERE message_type <> 'EMAIL_VERIFICATION' AND status IN ('SENT', 'CANCELLED', 'FAILED') AND next_attempt_at < now - INTERVAL '7 days'
    UNION ALL SELECT 'terminal_intake_cleanup_due', COUNT(*) FROM password_reset_requests r, stamp
      WHERE status IN ('DONE', 'CANCELLED', 'FAILED') AND completed_at < now - INTERVAL '24 hours'
        AND NOT EXISTS (SELECT 1 FROM password_reset_tokens t WHERE t.request_id = r.id)
    UNION ALL SELECT 'audit_checkpoint_missing', CASE WHEN exported_through IS NULL THEN 1 ELSE 0 END FROM stamp
    UNION ALL SELECT 'audit_checkpoint_in_future', CASE WHEN exported_through > now THEN 1 ELSE 0 END FROM stamp
    UNION ALL SELECT 'audit_export_lag_seconds', EXTRACT(EPOCH FROM now - exported_through) FROM stamp
    UNION ALL SELECT 'audit_unexported_events', COUNT(*) FROM auth_security_events, stamp
      WHERE exported_through IS NULL OR occurred_at > exported_through
    UNION ALL SELECT 'audit_retention_due', COUNT(*) FROM auth_security_events, stamp WHERE occurred_at < now - INTERVAL '30 days'
)
SELECT metric, value FROM metrics ORDER BY metric;
COMMIT;
