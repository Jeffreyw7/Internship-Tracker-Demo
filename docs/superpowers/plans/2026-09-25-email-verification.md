# Email Verification Implementation Plan

> **For agentic workers:** Use Superpowers test-driven development and verification. Execute the backend integration locally; delegate independent frontend and mail transport tasks through the parallel-agent workflow.

**Goal:** Require email confirmation for new accounts while preserving existing-account access.

**Architecture:** Persist verification challenges and encrypted delivery jobs in the current database. A scheduled SMTP worker delivers jobs; authentication only succeeds for verified or migration-exempt accounts. React presents verification screens and uses the existing session/CSRF transport.

**Tech Stack:** Java 17, Spring Boot 3.5, JDBC/JPA, Flyway, H2/PostgreSQL, React 19, TypeScript 5.9, Vite 8.

**Spec:** `docs/superpowers/specs/2026-09-24-email-verification-design.md`

## Global constraints

- Preserve session cookies, CSRF rotation, ownership checks, strict DTOs, theme account guards, and server-confirmed state.
- Only pre-migration accounts receive `email_verification_exempt=true`. New accounts default to false.
- Never log credentials or tokens; persist token hashes and authenticated-encrypted delivery payloads.
- Keep migration changes additive. Never operate on the user's local database during tests.
- Public POSTs still require CSRF; no auto-login after signup or confirmation and no automatic write replay.

## Review focus

- Existing-account migration must not falsely assert mailbox verification or exempt later inserts (Task 1).
- Concurrent confirm/resend and worker lease takeover must preserve single activation and bounded sends (Tasks 2–3).
- A lost HTTP response must leave a usable sign-in/resend recovery path (Tasks 2 and 4).
- Mail transport failure, crash, and payload corruption must not enable login or leak secrets (Task 3).
- Verification in another browser or while signed into another account must not replace the current account (Task 4).

## Task 1: Schema and authentication boundary

**Files:** new V3 migration, `CampusUser.java`, `CampusPrincipal.java`, `SecurityConfig.java`, `ApiException.java`, existing exception advice; new `EmailVerificationIntegrationTest.java`, `EmailVerificationMigrationTest.java`.

- [x] Write an HTTP test that registers a synthetic account and expects login with its correct password to return `403` and code `EMAIL_VERIFICATION_REQUIRED`; run against baseline and observe failure.
- [x] Write migration test: migrate to V2, insert a user, migrate latest, then insert another. Assert old `(exempt=true, verified=null)` and new `(exempt=false, verified=null)`.
- [x] Implement additive fields and authentication check after BCrypt validation, before successful authentication. Wrong passwords remain generic `401`.
- [x] Run targeted migration and HTTP tests.

Authentication rule:
```java
boolean allowed = user.getEmailVerifiedAt() != null || user.isEmailVerificationExempt();
```

## Task 2: Verification domain and API

**Files:** new `auth/verification/EmailVerificationService.java`, `EmailVerificationController.java`, `AuthRateLimiter.java`, `VerificationProperties.java`; update `AccountService`, `AuthController`, DTOs, SecurityConfig and integration fixtures.

**Interfaces:** service `issue(CampusUser user)` joins caller's transaction; `resend(String email)` returns generic accepted; `confirm(String token, String password)` consumes and activates atomically. `EmailOutbox.enqueue(UUID tokenId, String email, String token)` joins issuance transaction. Both verification and worker use the schema in V3.

- [x] Add failing tests for expiry, reuse, wrong password, CSRF, duplicates, forged exemption, concurrent confirmation, and resend limits.
- [x] Implement 32-byte random tokens, SHA-256 lookup, 24-hour expiry, consistent user-first locking, revocation and cancellation.
- [x] Add atomic DB rate counters for address, IP, token, and global budgets; suppress recipient-specific limit disclosures.
- [x] Return registration `202`; never overwrite duplicates; keep comparable password work. Add optional error `code`.
- [x] Update old tests' account setup to verify through a capture sender; run full backend suite.

API assertions:
```java
mvc.perform(post("/api/auth/email-verification/confirm").with(csrf())
    .contentType(MediaType.APPLICATION_JSON)
    .content(json.writeValueAsString(Map.of("token", token, "password", PASSWORD))))
    .andExpect(status().isOk());
```

## Task 3: Durable mail and operational configuration

**Files:** new `mail/EmailSender.java`, `SmtpEmailSender.java`, `EmailOutbox.java`, `EmailOutboxWorker.java`, `MailProperties.java`, `OutboxCrypto.java`; `pom.xml`, application YAML, mail unit/integration tests.

**Interfaces:** `EmailSender.send(String recipient, String token)` delivers a verification message. `EmailOutbox.enqueue(UUID tokenId, String email, String token)` persists AES-GCM encrypted JSON inside the caller's transaction. `EmailOutboxWorker.deliverPending()` exposes the same operation as the scheduler for deterministic tests. Token table fields: id, user_id, email, token_hash, created_at, expires_at, consumed_at, revoked_at. Outbox owns its table definition supplied to root for V3 integration.

- [x] Write failing tests for encrypted payload round-trip/tamper rejection, send success and erasure, retry, terminal failure, expired tokens, and lease takeover.
- [x] Add Spring mail dependency; isolate SMTP behind EmailSender. Use configured trusted origin and plain-text message, no tracking or logs of payloads.
- [x] Implement atomic lease claims, two-minute leases, bounded SMTP timeouts, six-attempt retry schedule and at-least-once semantics.
- [x] Validate production HTTPS, sender, SMTP and encryption key configuration; configure loopback SMTP capture for local development and test-only deterministic key/fake delivery.
- [x] Add cleanup and safe logging for failed/aged queues; run focused tests.

## Task 4: Frontend verification flow

**Files:** `src/data/api.ts`, `AuthGate.tsx`, new `EmailVerification.tsx`, fragment helper, CSS as needed, `tests/auth-verification.test.mjs`.

**Interfaces:** registration returns `{status:"VERIFICATION_REQUEST_ACCEPTED",message:string}`; resend same generic response; confirm returns `{status:"EMAIL_VERIFIED"}`. Login pending returns HTTP 403 and `code:"EMAIL_VERIFICATION_REQUIRED"`. Confirmation submits `{token,password}`.

- [x] Add failing transport tests asserting registration sends no login, confirmation includes CSRF and is not retried, structured codes survive, fragment extraction removes the secret.
- [x] Implement check-email, resend, explicit password confirmation, success, expired/error and normal login paths.
- [x] Parse `#verify-email=...` before auth rendering, remove it immediately, keep token in memory only, tolerate StrictMode and new links.
- [x] Preserve signed-in identity during verification, accessible labels/status messages, current theme tokens, and account-switch guards.
- [x] Run `npm test` and `npm run build`; review with Vercel React best practices.

## Task 5: End-to-end verification and documentation

**Files:** `tests/http_smoke.py`, new local SMTP capture helper if needed, `docs/API.md`, `docs/CODE_GUIDE.md`, `README.md`, `VALIDATION.md`, this ledger.

- [x] Extend bundled smoke test to capture SMTP in memory, extract the token, assert pending denial, confirm, then execute application and restart persistence checks.
- [x] Run `npm test`, `npm run build`, backend `mvnw.cmd test`, `mvnw.cmd -Pwith-ui package`, then `npm run test:smoke`.
- [ ] Verify PostgreSQL if a local isolated instance/runtime is available; otherwise report its explicit validation limit rather than implying compatibility.
- [ ] Perform browser verification and a fresh independent review; fix meaningful issues with regression tests.
- [x] Document environment settings, migration exemption boundary, provider setup, reliable delivery limits, and exact results.

## Execution ledger

- User explicitly requested implementation of the written design; proceeding without another authorization round. Existing checkout is `feature/dark-mode`; only preexisting change is the untracked approved design document. Work stays in the shared checkout so the user can review it directly; no branch integration, external publishing, or real emails are part of this task.
- Plan interfaces fixed before delegating frontend and mail work. Root owns migration/auth domain/backend fixtures and overall verification; delegates own disjoint frontend and mail files.
- Security review found and fixed rollback of failed-confirmation rate counters; an HTTP regression test now proves the eleventh rejected password receives `429 RATE_LIMITED`.
- The packaged smoke runner moved to Node because Node is required and the validation host had only the Windows Python launcher stub.
