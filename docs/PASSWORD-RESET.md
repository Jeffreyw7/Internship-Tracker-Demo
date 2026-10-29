# Password reset operations

The [system design](superpowers/specs/2026-10-10-password-reset-design.md) records the architecture and research sources. This implementation follows [OWASP recovery guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html): private responses, expiring single-use tokens, normal sign-in, notification and session invalidation.

## Review findings by category

| Category | Resolution and source |
| --- | --- |
| Recovery practices | Account-independent encrypted intake, neutral acceptance, 30-minute challenges and normal sign-in: `auth/reset/PasswordResetService.java`, `PasswordResetRequestWorker.java`, `src/components/ResetPassword.tsx` |
| Security | Atomic password/epoch/consumption/audit/notice, primary-clock expiry after locks, cookie-free passive rejection: `PasswordResetService.java`, `auth/CredentialVersionFilter.java`, `PostgresPrivilegesIT.java` |
| Maintainability | Explicit short transactions, purpose-bound encryption, bounded retries and cleanup: `mail/OutboxCrypto.java`, `EmailOutboxWorker.java`, `auth/reset/ResetMaintenance.java` |
| Admission | Recipient suppression preserves global mail capacity, separate notice budget, bounded body processing: `verification/AuthRateLimiter.java`, `ResetInputFilter.java`, `RecoveryCapacity.java` |
| Independent review | Removed post-commit database read, validated blocklist at startup, bounded slow bodies, preserved recovery forms during focus refresh: `PasswordResetController.java`, `config/SecurityConfig.java`, `config/RequestReadLimits.java`, `src/components/AuthGate.tsx` |
| October 15 follow-up review | Three parallel GPT-6.1 Sol High reviews checked recovery practices, maintainability and security. One low-severity finding was fixed: health/maintenance fallback writes now acquire database admission; saturation defers work. `auth/reset/ResetHealth.java`, `ResetMaintenance.java`, `ResetBackgroundAdmissionTest.java` |
| October 29 session security review | Delayed API rejection and reset completion clear request-local authentication instead of invalidating a session reused by a newer login. Login persists authentication only after a primary epoch check under the session mutex; stale or unavailable checks preserve another login. Every old principal still fails the primary credential-epoch check: `auth/CredentialVersionFilter.java`, `config/SecurityConfig.java`, `auth/reset/PasswordResetController.java`, `CredentialSessionRaceTest.java`, `PasswordResetPostCommitTest.java` |
| October 29 recovery interface review | An unavailable initial session check offers retry without exposing recovery controls; background failures preserve the known account: `src/components/AuthGate.tsx`, `tests/password-reset-ui.test.mjs` |
| October 29 operations review | Health pause includes overdue eligible reset-link mail even after intake becomes DONE, and clears when it drains. Expired, consumed, revoked and mismatched links and password-change notices are excluded: `auth/reset/ResetHealth.java`, `PasswordResetIntegrationTest.java` |

Java source paths are relative to `backend/src/main/java/com/campus/tracker/`; tests are under `backend/src/test/java/com/campus/tracker/`.

The October 29 review/fix/review loop used independent GPT-6.1 Sol High agents. Final frontend, operations and security reviews found no further actionable defects in their inspected scope. Tests reproduced the session and backlog failures before fixes. Login publication required configuring the built filter through `ObjectPostProcessor`: installed Spring Security 6.5.11 replaces the earlier fluent repository setting during configuration. This behavior was checked against the installed bytecode and the failing regressions. [Spring's persistence documentation](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/persistence.html) explains explicit context saving; [OWASP recovery guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html) supports automatic session revocation and ordinary sign-in after reset. Both references were rechecked October 29. Local execution results and remaining deployment gates are in [VALIDATION.md](../VALIDATION.md).

The follow-up reviewers found no additional concrete recovery-practices or maintainability defect in the inspected scope. Research checked [OWASP recovery guidance](https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html), [Spring programmatic transactions](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html), and PostgreSQL [transaction isolation](https://www.postgresql.org/docs/17/transaction-iso.html), [wall-clock timestamps](https://www.postgresql.org/docs/17/functions-datetime.html) and [column grants](https://www.postgresql.org/docs/17/sql-grant.html). The security reviewer inspected the admission fix and confirmed that it addresses the finding. These are scoped source reviews; deployment release gates remain below.

## Activation

V4 starts recovery disabled. Stop old binaries before migration: they cannot enforce credential epochs. Existing PostgreSQL installations must first run `deploy/postgres/provision-retention.sql` as administrator with `RETENTION_DATABASE_PASSWORD` in the environment, then run the existing `MigrationMain` job. Never give administrator, migrator or retention credentials to the web process.

Production requires independent `RESET_AUDIT_SECRET` (32+ characters), `RESET_AUDIT_KEY_ID`, `VERIFICATION_RATE_LIMIT_SECRET`, AES-256 `OUTBOX_ENCRYPTION_KEY`, `OUTBOX_KEY_ID`, HTTPS `PUBLIC_ORIGIN`, HTTPS `HELP_DESTINATION`, database and SMTP settings. Supply a maintained UTF-8 `PASSWORD_BLOCKLIST_FILE`; unreadable configuration prevents startup. The packaged baseline is small. Password creation uses 15 Unicode characters without composition rules and a 72 UTF-8 byte BCrypt cap; this does not establish comprehensive compromised-password coverage or full NIST compliance.

After staging and operational release checks, enable through an administrator connection:

```sql
UPDATE password_reset_control SET enabled=TRUE, operator_intake_paused=FALSE WHERE id=1;
```

Runtime reads the singleton and can update only its health pause. Independent health checks pause intake for unavailable/stale workers, overdue maintenance, pending intake or eligible pending/sending reset-link mail older than two minutes. Completed intake does not hide an undelivered link. The mail check excludes expired, consumed, revoked or account-mismatched tokens and password-change notices. Operator intake pause still permits confirmation; full disable stops intake, confirmation and reset-link dispatch. Password-change notices continue.

For disposable local H2 development, pass `--campus.reset.development-enabled=true`; the setup bean is excluded from production. Match `PUBLIC_ORIGIN` to the frontend origin and use an SMTP capture inbox.

## Bounds and rotation

Reset requests are uncompressed JSON of at most 4096 bytes. Four ingress permits apply before reading the body. Six database-work permits plus the two independently bounded mail workers keep recovery database work within eight concurrent operations. Tomcat has a five-second upload inactivity limit; the filter checks a five-second elapsed deadline between reads, so one blocking read can extend total time to about ten seconds. The edge must impose a total request deadline and connection/body admission. See [Tomcat connector documentation](https://tomcat.apache.org/tomcat-10.1-doc/config/http.html).

Intakes expire after ten minutes, with 30-second fenced leases and five maximum claims. Challenges expire after 30 minutes. Reset mail allows two attempts with a two-minute lease; SMTP has a 55-second total deadline. Uncertain SMTP acceptance can duplicate delivery and does not prove inbox receipt. Failed notification never rolls back a password change.

Intake quotas: recipient 1/minute, 5/hour, 10/day; IP 5/minute, 30/hour; global 100/minute, 10,000/day. Confirmation: token 10/15 minutes, IP 10/minute, global 60/minute. Actual verification/reset-link issuance shares 1000/day; notices have a separate 1000/day budget. Durable HMAC counters survive restart.

Deploy AES decrypt keys under `campus.mail.decrypt-keys` before switching the write key; retain old keys until pending payloads drain or are cancelled. Rotate limiter secrets with overlapping `campus.verification.previous-rate-limit-secrets` through the longest active window; every overlapping budget must pass. Audit HMAC has its independent key ID. Never log secrets, passwords or recovery payloads.

## Retention, rollback and restore

Maintenance erases expired/terminal payloads in bounded phases. Terminal mail/challenges retain seven days; completed intake retains at least 24 hours and until referenced challenges are gone. Audit retention uses the separate `campus_retention` identity with SELECT(id,occurred_at) and DELETE only. Commit each bounded batch:

```sql
DELETE FROM auth_security_events WHERE id IN (
 SELECT id FROM auth_security_events
 WHERE occurred_at < clock_timestamp() - INTERVAL '30 days'
 ORDER BY occurred_at,id LIMIT 1000
);
```

Keep recovery disabled during mixed-version rollouts or incidents. Roll forward fixes after V4; never downgrade to binaries that ignore epochs or edit applied migrations. Backup restores can resurrect tokens and old credentials: isolate traffic, disable recovery, revoke challenges, cancel and erase queued recovery payloads, and clear every session store before restoring access. Coordinate recovery for password changes lost by restore.

Before public activation, record proxy deadline behavior, production load/latency and backlog alerts, SMTP domain/provider delivery, supported browser/mail-client fragment handling, and durability/failover/restore exercises. Sanitized logs and database health state are implemented; a production dashboard and measured SLO evidence remain operator work. [VALIDATION.md](../VALIDATION.md) records local checks and limitations.
