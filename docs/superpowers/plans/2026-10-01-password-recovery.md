# Password Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Deliver email-link password recovery and current-password-plus-email-confirmed password changes, with atomic credential replacement and revocation of existing sessions.

**Architecture:** Keep React and Spring Security sessions. Persist purpose-bound, hashed password challenges and encrypted transactional mail jobs in the existing database; deliver through a leased SMTP worker. Check a database credential version on authenticated API requests.

**Tech Stack:** React 19, TypeScript 5.9, Spring Boot 3.5, Java 17, Spring Data JPA/JDBC, Flyway, H2/PostgreSQL, Spring Mail, AES-GCM.

**Spec:** `docs/superpowers/specs/2026-09-30-password-recovery-design.md`

## Verified baseline and necessary adaptation

The checkout inspected on October 1 differs from the checkout described in the spec: only migration V1 exists; signup signs in immediately; there is no signup verification subsystem, theme provider, encrypted outbox, rate limiter, or verification-link parser. The spec file remains present. Build the missing password-specific infrastructure rather than calling nonexistent services or adding unrelated signup behavior.

Use the next migration, `V2__password_recovery.sql`. All current accounts are eligible for recovery because this checkout has no verified/pending distinction. Preserve existing signup and application behavior. Verification for this feature means successful presentation of a freshly emailed password challenge. There are no existing signup mail jobs to backfill or retain compatibility with. If the checkout changes before execution, inspect migrations and auth/mail files again before applying this adaptation.

## Global Constraints

- RESET tokens expire after 30 minutes; CHANGE tokens after 15 minutes.
- Generate 32 secure random bytes, encode unpadded base64url, and persist only SHA-256 token hashes outside encrypted mail payloads.
- Keep BCrypt cost 12 and the existing 12-character complexity policy, including its 72-UTF-8-byte maximum.
- All writes retain session-cookie and CSRF protection; only reset request/confirm become public.
- CHANGE requires current password at issuance and the initiating account/session at confirmation.
- Confirmation revokes all password challenges and all sessions through an incremented credential version; no automatic sign-in.
- Never automatically replay failed writes or store credentials/tokens in browser storage.
- Preserve existing application ownership, data, pagination, and signup contracts.
- No passwords, token secrets, encryption keys, or email addresses in logs or committed configuration.
- Use a configured public origin for links; require HTTPS outside loopback development.
- Apply new Flyway migrations without editing V1; keep Hibernate schema validation.

## Review Focus

1. A reset link opened while another account is signed in must reset only the emailed account (Task 3 and Task 5 tests).
2. Two confirmations or a concurrent issuance/confirmation must have one consistent account state (Task 3 concurrent integration tests).
3. A worker crash after SMTP acceptance can cause duplicate mail; replay must still fail and leases must recover (Task 2 tests).
4. A lost confirmation response must not cause automatic resubmission or stale frontend authentication (Task 5 transport/state tests).
5. Existing users and queued jobs must survive application restart; encryption configuration must stay stable across restarts (Task 6 smoke tests).

## Task 1: Password persistence and durable rate limits

**Files:** Create `backend/src/main/resources/db/migration/V2__password_recovery.sql`, `backend/src/main/java/com/campus/tracker/auth/PasswordChallengeRepository.java`, `backend/src/main/java/com/campus/tracker/auth/PasswordToken.java`, `backend/src/main/java/com/campus/tracker/auth/AuthRateLimiter.java`, and `backend/src/test/java/com/campus/tracker/PasswordPersistenceTest.java`. Modify `CampusUser.java`, `UserRepository.java` in the auth package, and test configuration.

**Interfaces:** `PasswordToken.generate(): String`, `PasswordToken.hash(String): String`; `UserRepository.findByIdForUpdate(UUID): Optional<CampusUser>`; `CampusUser.replacePassword(String hash, Instant changedAt): void`, `getCredentialVersion(): long`. `PasswordChallengeRepository` provides insert, hash lookup, locked hash lookup, account revocation, and expiry cleanup. Challenges expose UUID/account/purpose/email/version/binding hash and lifecycle instants. `AuthRateLimiter.requireRequestAllowed(String scope, String address): void`, `reserveEmail(String email): boolean`, and `requireConfirmationAllowed(String token, String address): void` run counters in independently committed transactions.

- [ ] Write tests asserting token format `[A-Za-z0-9_-]{43}`, unequal generated tokens, 64-character hashes, unique-hash enforcement, purpose/binding constraints, persisted credential version, and concurrent first-bucket insertion without 500 responses. Assert request limits at 5/minute and 30/hour, recipient limits at 1/minute, 5/hour, 10/day, shared mail budget at 1000/day, confirmation limits at 10/minute/IP and 10/15-minutes/token, and counter durability after a rejected transaction.
- [ ] From `backend/`, run `.\mvnw.cmd -Dtest=PasswordPersistenceTest test`; confirm missing-feature failures before adding implementation.
- [ ] Add credential version/change timestamp, password challenges, encrypted outbox schema, and rate-limit buckets. Use UTC instants, UUID foreign keys, indexes, and purpose-specific checks. Build rate-limit bucket creation with an insert in a separate transaction that can safely roll back on conflict, followed by atomic bounded update; retry a concurrent insert conflict without swallowing unrelated database failures. Scope identifiers are HMAC protected; limit settings are configurable.
- [ ] Implement locks with consistent account-before-challenge ordering, random token utilities, and password setters. Keep sensitive object string representations redacted.
- [ ] Rerun the targeted tests. Run the existing backend tests to verify registration/schema compatibility.

## Task 2: Encrypted transactional mail and SMTP delivery

**Files:** Create `backend/src/main/java/com/campus/tracker/mail/{MailProperties,MailConfiguration,EmailMessage,EmailSender,SmtpEmailSender,OutboxCrypto,EmailOutbox,EmailOutboxWorker}.java` and `backend/src/test/java/com/campus/tracker/{MailOutboxTest,OutboxCryptoTest}.java`. Modify `backend/pom.xml`, `application.yml`, `application-prod.yml`, and `application-test.yml`.

**Interfaces:** `EmailMessage` carries type RESET/CHANGE/CHANGED, recipient, optional raw challenge token, and event timestamp; redact `toString()`. `EmailSender.send(EmailMessage): void`; `EmailOutbox.enqueueChallenge(UUID challengeId, EmailMessage): void` and `enqueueNotification(UUID userId, EmailMessage): void` require an existing transaction. `EmailOutboxWorker.deliverPending(): void` claims work with short transactions and performs SMTP outside transactions. `OutboxCrypto` encrypts/decrypts versioned JSON with AES-GCM, random 12-byte nonces, key ID, and job ID as authenticated additional data. Clock is injected throughout.

- [ ] Write tests for rollback removing both challenge/job, encrypted recipient/token absence from stored ciphertext, authenticated-data tampering rejection, correct link fragments and TTL copy, notification delivery after challenge consumption, expired/revoked/version-mismatched mail cancellation, two-worker claim exclusivity, retry exhaustion, duplicate-delivery replay safety, and lease recovery after restart.
- [ ] Run `.\mvnw.cmd -Dtest=MailOutboxTest,OutboxCryptoTest test`; observe expected failures.
- [ ] Add Spring Mail using Boot dependency management. Bind `campus.password.mail-enabled` to `PASSWORD_MAIL_ENABLED` (default false), `campus.password.public-origin` to `PASSWORD_PUBLIC_ORIGIN`, `campus.password.mail-from` to `PASSWORD_MAIL_FROM`, `campus.password.encryption-key` to `PASSWORD_ENCRYPTION_KEY` (base64-encoded 32 bytes), `campus.password.encryption-key-id` to `PASSWORD_ENCRYPTION_KEY_ID`, and `campus.password.rate-limit-secret` to `PASSWORD_RATE_LIMIT_SECRET` (minimum 32 bytes). TTL properties are `campus.password.reset-ttl: 30m` and `campus.password.change-ttl: 15m`. Use Spring's `spring.mail` properties for SMTP credentials and TLS, with 5-second connection/read/write timeouts. Validate sender, origin, and secrets at startup when enabled. Password request endpoints return generic 503 when disabled. Tests supply synthetic keys and an injected test sender; development never prints tokens or silently pretends to send mail. Document enabled deployment configuration in Task 6.
- [ ] Implement encrypted enqueueing and scheduled leased worker: batch 25, lease two minutes, six total attempts, jittered delays 60/300/900/3600/14400 seconds. Recheck challenge eligibility immediately before sending; clear secret payload on SENT/CANCELLED/FAILED. Retry transient provider failures, terminate permanent failures, cancel expired jobs, and retain terminal metadata seven days. Cleanup removes dependent jobs before expired challenges and rate-limit buckets. Notification eligibility is independent of challenge consumption.
- [ ] Rerun tests and confirm disabling the mail feature keeps existing backend tests/startup operational.

## Task 3: Password API and atomic replacement

**Files:** Create `backend/src/main/java/com/campus/tracker/auth/{PasswordDtos,PasswordController,PasswordService}.java` and `backend/src/test/java/com/campus/tracker/PasswordIntegrationTest.java`. Modify `common/ApiException.java`, `common/ApiErrorHandler.java`, `auth/PasswordPolicy.java`, and `config/SecurityConfig.java`.

**Interfaces:** `PasswordService.requestReset(String email): PasswordDtos.Result`; `requestChange(UUID accountId, String currentPassword, String sessionBinding): PasswordDtos.Result`; `confirmReset(String token, String newPassword): UUID`; `confirmChange(UUID accountId, String sessionBinding, String token, String newPassword): UUID`. Controller applies request/attempt limits, generates a fresh session binding on successful CHANGE issuance, returns the spec's statuses, and ends a matching local session after confirmation commits. Keep existing exception constructors; add optional code/retry-after support.

- [ ] Add MockMvc tests for all four spec routes: generic known/unknown/suppressed reset 202, CSRF 403, anonymous CHANGE 401, wrong current password 400, policy errors without token consumption, unknown-field/oversized-field rejection, expiry exactly at deadline, malformed token, wrong purpose, wrong account/session, changed email/version, and no mutation from GET/link preview. Verify codes and no-store headers.
- [ ] Add service/database tests for one winner under concurrent confirmation, atomic rollback including failed enqueue, issuance racing with confirmation, invalidation of all challenge purposes on success, anonymous resends preserving earlier RESET links, and authenticated new CHANGE issuance replacing earlier changes. Assert unchanged application records and account metadata.
- [ ] Run `.\mvnw.cmd -Dtest=PasswordIntegrationTest test`; verify failures before implementing the routes.
- [ ] Implement validated bounded DTOs with redacted string representations; tokens are exactly 43 base64url characters. Apply limits before database/password work. Normalize email as login does, perform comparable dummy hashing for unknown reset requests, and avoid synchronous provider calls. Account existence remains confidential in request responses. Return generic unavailable errors for enqueue/infrastructure failures.
- [ ] Implement account-first locking and final validation before consuming a token. Within one transaction replace BCrypt hash, increment version, timestamp change, consume winner, revoke others, cancel pending challenge jobs, and enqueue notification. Do not set signup verification metadata. Notification SMTP failure after commit does not roll back credentials.
- [ ] Allow only the two RESET POST routes publicly; retain CSRF protection. Return `PASSWORD_CHALLENGE_INVALID`, `PASSWORD_POLICY_FAILED`, `CURRENT_PASSWORD_INVALID`, and rate-limit responses as specified. Keep confirmation results explicit; invalidate only the target account's local session when RESET is completed from a browser authenticated as a different account.
- [ ] Rerun targeted and existing backend tests.

## Task 4: Revoke old sessions across instances

**Files:** Create `backend/src/main/java/com/campus/tracker/auth/CredentialVersionFilter.java` and `backend/src/test/java/com/campus/tracker/CredentialVersionIntegrationTest.java`. Modify `CampusPrincipal.java`, `UserRepository.java`, and `SecurityConfig.java`.

**Interfaces:** Add `long credentialVersion` to the principal factory and a lightweight repository version projection. The filter runs after context loading and before authorization on authenticated `/api/**` requests. Avoid separately registering it as a servlet filter.

- [ ] Write tests with two login sessions: both work before confirmation, both return 401 afterward, old-password login fails, new-password login succeeds, and a new session has the current version. Cover missing account, stale principal during login/reset race, and failed version lookup never authorizing a protected request.
- [ ] Run `.\mvnw.cmd -Dtest=CredentialVersionIntegrationTest test`; confirm expected failures.
- [ ] Implement version comparison without stale caching. On mismatch clear context, invalidate session, expire JSESSIONID, and return generic no-store JSON 401. Database unavailability returns generic 503 without allowing the request. Successful completion ends its local matching session after transaction commit.
- [ ] Rerun tests, including existing session/login/logout integration tests.

## Task 5: React recovery and email-confirmed change screens

**Files:** Create `src/domain/passwordLink.ts`, `src/components/{PasswordRecovery,PasswordCompletion,AccountSecurity}.tsx`, and `tests/password.test.mjs`. Modify `src/data/api.ts`, `src/components/AuthGate.tsx`, `src/App.tsx`, `src/styles.css`, `index.html`, and `package.json`.

**Interfaces:** `PasswordLink = { purpose: 'reset' | 'change'; token: string | null }`; `readPasswordLink(location, history): PasswordLink | null` captures then removes fragments. `passwordApi.requestReset(email)`, `requestChange(currentPassword)`, `confirmReset(token,newPassword)`, and `confirmChange(token,newPassword)` use shared transport. Add optional `ApiError.code` and explicit CSRF reset after credential replacement. AuthGate owns account/login/completion state and passes `onSecurity` to App.

- [ ] Add Node tests for purpose parsing, token validation, immediate fragment removal, no browser-storage writes, CSRF and exact endpoint bodies, token/code preservation, credentials and no-store behavior, no retry on uncertain completion, and stale responses after account switch/session expiry. Expand npm test to include all `tests/*.test.mjs`.
- [ ] Run `npm test`; verify new tests fail on missing behavior.
- [ ] Implement focused forms with labels, password-manager autocomplete, paste support, confirmation comparison, shared password rules, busy guards, and accessible errors/success. Add Forgot password on login and Account security in the workspace. Keep token/password values only in memory and clear them when dismissed/completed. Parse fragments at app initialization before auth checks and network calls; add no-referrer policy and avoid third-party assets.
- [ ] CHANGE completion checks authenticated initiating browser requirements and guides restart on expired/wrong-session links. RESET targets the emailed account even when another user is signed in; after success return to login with coherent server/frontend session handling. Abort or ignore stale account mutations and ensure expired-session events clear CSRF/account data. No optimistic password-success messages.
- [ ] Run `npm test` and `npm run build`. Apply Vercel React checks for stable effects, event listener cleanup, component boundaries, strict types, and accessibility. Verify actual browser login/recovery/change flows with a test mail sink; do not use production mail or real user accounts.

## Task 6: Deployment documentation, end-to-end verification, and security review

**Files:** Modify `README.md`, `docs/API.md`, `docs/CODE_GUIDE.md`, `VALIDATION.md`, and `tests/http_smoke.py`; add targeted test helpers for an isolated SMTP sink and PostgreSQL verification where available.

**Interfaces:** Document `PASSWORD_MAIL_ENABLED`, trusted public origin, SMTP host/port/user/password/from/TLS/timeouts, AES key/key ID, HMAC secret, TTLs and rate-limit configuration using the exact property names introduced in Task 2. Keep secrets supplied through environment and uncommitted local configuration. Smoke tests use synthetic accounts, temporary databases, loopback SMTP, and stable test keys across restart.

- [ ] Extend smoke checks to assert real email-link recovery across restart, CHANGE in its initiating session, rejection in another browser, all old sessions rejected, replay rejection, old/new password behavior, and unchanged application history. Assert outbox startup/lease recovery and password jobs persisted without plaintext token columns. Do not delete or open the user's normal H2 database.
- [ ] Run `npm test`, `npm run build`, `.\mvnw.cmd test` from backend, `.\mvnw.cmd -Pwith-ui package`, and `python tests/http_smoke.py`. Verify Flyway/schema and concurrency on PostgreSQL with isolated credentials/database if available; report any unavailable check explicitly.
- [ ] Review frontend browser flows and screenshots for narrow/wide layouts, keyboard navigation, readable errors, and uncertain network outcomes.
- [ ] Apply Codex Security's security-diff-scan workflow to an immutable final patch, including its capability/config preflight, threat model, changed-file inventory, discovery, validation of candidates, and report. Review token leakage, unauthorized change, mail payload encryption, CSRF, session revocation, rate-limit concurrency, and atomic replay prevention. Fix confirmed findings within authorized feature scope and rerun affected tests; do not publish findings externally.
- [ ] Record actual checks, results, and PostgreSQL/browser limitations in VALIDATION.md. Run `git diff --check` and inspect final diff. Leave unrelated files and changes untouched.

## Completed execution — October 2, 2026

- [x] Task 1: Persistence, hashed challenges and durable atomic rate limits.
- [x] Task 2: Encrypted transactional outbox, SMTP delivery, leases/retries/cleanup and mail configuration.
- [x] Task 3: Recovery and initiating-session email-confirmed password change.
- [x] Task 4: Credential-version session revocation and fail-closed lookup checks.
- [x] Task 5: React forms, transient link handling, no-replay uncertainty behavior and browser verification.
- [x] Task 6: Documentation, real HTTP/SMTP/restart checks, independent review and security scan/fix.

The detailed steps above record the original proposed sequence. Test files/commands were consolidated into `PasswordIntegrationTest`, `CredentialVersionFilterTest`, and `tests/password.test.mjs`; the execution record documents actual RED/GREEN checks and necessary adaptations. PostgreSQL and production SMTP/deployment remain unavailable verification environments, as recorded in `VALIDATION.md`. No branch integration or deployment was requested.

## Execution handoff

Recommended method: native execution in this session. The persistence, mail, password service, and security filter share transaction and payload contracts, so keeping implementation together reduces handoff overhead. The writing-plans skill requires review of this concrete plan and selection of execution method before implementation. No product code has been changed during planning.
