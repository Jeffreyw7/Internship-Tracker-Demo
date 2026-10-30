# Password Reset Implementation Plan

> **For agentic workers:** Use the implementation-planning, parallel-agent and TDD workflows. The user authorized resolving the reviewed gaps and implementation in this session.

**Goal:** Recover an existing account through a private emailed link, preserve account data, atomically replace credentials and revoke previous sessions.

**Architecture:** Extend the existing monolith with encrypted database intake, opaque digest-backed challenges and typed mail purposes. Account-first locks and explicit transaction blocks protect completion; primary database time and independently committed quotas protect expiry and admission.

**Tech Stack:** Java 17, Spring Boot 3.5.16, JDBC/JPA, Flyway V4, H2/PostgreSQL, React 19, TypeScript 5.9.

**Spec:** ../specs/2026-10-10-password-reset-design.md

## Global constraints

- Remain on Password-Reset in the authorized workspace; no deployment, production migrations or real email.
- Canonical 32-byte random tokens, 30-minute expiry, 10-minute intake deadline, 30-second intake leases; strict 4 KiB JSON body contract.
- Same-origin cookies and CSRF, no write replay, no plaintext token persistence/logging, account-independent 202.
- Existing pending accounts remain verification gated. Password changes preserve applications and appearance.
- Password creation: 15 Unicode code points, no composition rules, common-password blocklist, at most 72 UTF-8 bytes. Existing login passwords remain valid.
- Production recovery starts disabled in a database singleton; enable only through private operator configuration and recorded release evidence.

## Interfaces and file ownership

- Core (parent): `auth/reset/*`, V4, backend PasswordPolicy/blocklist, YAML configuration, API/docs/validation and end-to-end smoke.
- Mail task: `mail/*`, verification AuthRateLimiter/EmailVerificationService, focused mail/limiter tests. Preserve old public constructors with overloads where tests instantiate components.
- Session/deployment task: CampusUser/CampusPrincipal, SecurityConfig, `auth/CredentialVersionFilter`, deploy/postgres scripts, PostgresPrivilegesIT, session tests.
- Frontend task: `src/data`, `src/domain/password*`, AuthGate, recovery screens and relevant Node tests. Preserve ownership/version transport behavior and theme boundaries.

## Task 1: Schema, admission and completion core

- [x] Add failing HTTP tests: known/unknown equal acceptance, no account lookup at intake, CSRF, strict fields/size/token errors, successful atomic completion, pending-account preservation, sibling/concurrent single use, cutoff and rollback.
- [x] Add V4 account epochs/cutoff, intake/control/challenge/audit and generalized outbox schema, indexes and lifecycle constraints.
- [x] Implement `DatabaseTime.now(): Instant`, `PasswordResetService.request(email, ip): Accepted`, `confirm(token, newPassword, ip): Accepted`, and `PasswordResetRequestWorker.processPending(): void` with explicit transaction boundaries, leased retries and terminal payload erasure.
- [x] Implement shared password policy and packaged/supplemental blocklist; test policy parity and legacy login compatibility.
- [x] Run targeted tests then backend suite. Capture failure/pass evidence.

## Task 2: Generalized mail and committed quotas

- [x] Test suppressed recipient traffic leaves global issuance capacity, key-ring legacy/new/tampered jobs, reset/notice eligibility, notice template/report path, retry lease fencing and erasure.
- [x] Expose verification limiter `requireResetRequestAllowed(ip)`, `reserveResetAddress(email)`, `requireResetConfirmationAllowed(token, ip)`, `reserveLinkMail()`, `reserveNoticeMail()`, and `subjectHash(value)`; reset limits always enabled and primary database time.
- [x] Expose `EmailOutbox.enqueueReset(tokenId,email,token)` and `enqueuePasswordChanged(tokenId,email,occurredAt)` in mandatory caller transactions. Typed encrypted purposes must match database purpose; legacy verification payloads remain readable.
- [x] Generalize worker and sender without changing verification behavior; use a decrypt key ring, bounded retries and separate notice budget/deadline. No DB transaction during SMTP.
- [x] Run targeted mail/limiter tests; report integration assumptions.

## Task 3: Session epoch and PostgreSQL privileges

- [x] Test old-session rejection, other account preservation, missing/legacy epochs, database failures, login/reset race and absence of stale-response cookie deletion.
- [x] Add CampusUser credential getters and `replacePassword(hash,cutoff)` plus principal epoch snapshot/redacted toString. Read current epoch uncached on primary before protected API dispatch; recheck after authentication.
- [x] Permit public reset POST routes while retaining CSRF; harden reset/root response headers, body errors and filter ordering.
- [x] Update exact credential-column grants, sequence USAGE, reset table DML, audit SELECT/INSERT, control health-only UPDATE; separate retention SELECT(id,occurred_at)/DELETE identity and positive/negative PostgreSQL tests.
- [x] Run backend and real PostgreSQL checks where available.

## Task 4: Frontend recovery and authentication coordination

- [x] Test strict canonical fragment capture/scrubbing, hash changes/StrictMode, no persistence; typed request/confirm contracts, no automatic replay, failed writes, stale CSRF and auth transition ordering.
- [x] Add `passwordResetApi.requestReset(email)` and `.confirmReset(token,newPassword)`, ForgotPassword/ResetPassword screens, explicit logout for authenticated link visitors, neutral recovery/help/verification guidance and success memory erasure.
- [x] Serialize intentional auth/session-creating transitions through a browser-wide Web Lock with local fallback; token-free cross-tab invalidation/refetch. Keep passive stale rejection cookie-free on server.
- [x] Update frontend password policy and existing policy tests. Run npm test and npm run build.

## Task 5: Integration, maintenance and evidence

- [x] Add bounded separate maintenance phases for deadlines/ciphertext/retention and private worker health admission pause; use stable locking and fail-closed errors.
- [x] Configure worker capacities, primary DB time/timeouts, feature control and key/HMAC overlap support; document safe activation, rotation, retention, rollback and restore.
- [x] Run frontend/backend suites, real PostgreSQL privileges/locking, bundled HTTP/restart smoke with synthetic accounts. Record environmental limits rather than claim unmeasured SLO/deliverability.
- [x] Obtain independent whole-change review using GPT-6.1 Sol High; fix substantive findings with regression tests and rerun affected/full suites.

## Review focus

- Delayed stale protected response headers must not clear a new session cookie (task 3/4).
- Suppressed verification requests cannot consume actual reset issuance budget (task 2).
- Retention role can delete aged audit rows but runtime cannot delete any (task 3).
- Request sequence allocated between cutoff and reset commit follows allocation-order guarantee (task 1).
- Restart/crash/lease takeover cannot duplicate issuance or successful completion (task 1/2/5).

## Execution record

Plan self-review: schema, flows, mail, sessions, privacy, operations and release requirements mapped above. Deployment SLOs, provider/domain configuration, production restore/failover and supported mail-client behavior require operator evidence; local implementation cannot establish them. Parallel tasks have disjoint file ownership and the interfaces above. Parent integrates and verifies all results.

Implementation verified October 12, 2026: 61 frontend tests, TypeScript/Vite build, 112 backend unit/H2 tests, and three PostgreSQL18 privilege/recovery checks passed. Independent review completed; four findings addressed with startup, post-commit failure, body-admission/timeout and mounted-focus regressions. Bundled real HTTP/SMTP-capture/restart recovery passed. Final packaging includes the patched Vite8.0.16 toolchain and wrapped socket-timeout regression. No deployment or user database migration performed. External release gates remain in docs/PASSWORD-RESET.md; checked tasks mean local engineering work completed, not operational production certification.
