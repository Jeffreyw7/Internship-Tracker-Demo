# Validation record

## Job posting and contact details — October 3, 2026

Implemented on `design/job-posting-contact-details`, using the approved architecture and implementation plan. Checks used synthetic accounts, isolated H2 databases and a separate loopback PostgreSQL 18.6 test instance on port 15435. The normal application databases and installed PostgreSQL service were not used.

| Check | Result |
| --- | --- |
| Complete Java suite, with temporary PostgreSQL migration configuration | 74 passed; zero failures, errors, or skips |
| Client/domain/API/component tests (`npm test`) | 32 passed |
| Strict TypeScript and Vite build (`npm run build`) | Passed |
| Bundled executable JAR (`backend/mvnw.cmd -Pwith-ui -DskipTests package`) | Passed |
| Upgrade existing V3 rows on H2 and PostgreSQL | Passed: accounts/application notes, soft-deleted state, versions, interview stages and reminder preferences retained; empty posting defaults applied |
| Real HTTP tests on file-backed H2 and PostgreSQL | Passed: real CSRF/session auth, capture/history, old-client metadata preservation, optimistic conflicts, delete/undo, ownership isolation, restart persistence |
| Simultaneous snapshot writers and failure after snapshot insertion | Passed: one concurrent writer wins; failure rolls back parent metadata, version and new snapshot |
| Desktop and 390px browser review | Passed: existing values load, description/recruiter edits persist, original history remains, pasted HTML renders as text, compensation decimals/units display correctly |
| Browser failure/retry and concurrent-edit flows | Passed: failed description read disables description edits while allowing metadata-only saves; retry retains other draft fields; stale save retains the draft and offers explicit reload |
| Archive generation in browser | Passed: actual generated JSON Blob includes all three captured revisions, original Unicode/line breaks, and exact `25.10` compensation |
| Browser filesystem download capture | Limited: headless browser canceled existing CSV, new JSON, and an independent data-URL download probe, even with an explicit directory; file saving was not verified in this automation environment |

The posting tests were run failing before implementation. Full suite verification caught and corrected the returned JPA version after creating a first snapshot. The concurrency test was adjusted to use explicit executor shutdown for the project's Java 17 target.

Independent code review found no material bugs. One minor is deferred: a stale workspace can display old scalar metadata beside a newer description until the workspace is refreshed; editor initialization and writes still check versions and cannot overwrite newer data. Browser interactions cover description failure/retry/conflict behavior; automated component tests currently use static rendering.

PostgreSQL migration/HTTP checks passed, but the installed Flyway version warns that PostgreSQL 18 is newer than its tested support range (through 17). No dependency upgrade was included. Capture/history is manual, with one primary recruiter/referral source and no snapshot purge endpoint; existing soft-delete retention continues.

`node tests/posting_http_smoke.mjs` builds no product dependencies and starts/stops its own temporary bundled backend. Run it after bundling; optional `--serve` keeps the synthetic instance open until Ctrl+C. For a disposable PostgreSQL database, set `POSTING_SMOKE_POSTGRES_URL` and `POSTING_TEST_POSTGRES_USER`. `PostingMigrationTest` additionally uses `POSTING_TEST_POSTGRES_URL`; its PostgreSQL case skips when this variable is absent. Logs, screenshots, synthetic account state, and browser probes are ignored under `test-results/job-posting/`.

## Automated reminder review fixes - October 2, 2026

Fixed three additional review findings on `Autonamted-Reminders-Feature`: same-day interviews/deadlines due after the digest cutoff could expire without notification; overview initialization could introduce a user/preference lock cycle; and a delayed reminder link could replace a newer application drawer. Regression tests reproduced each failure before the corresponding fix.

| Check | Result |
| --- | --- |
| Complete Java suite (`backend/mvnw.cmd test`) | 66 passed, no failures/errors/skips |
| Client tests (`npm test`) | 25 passed |
| Strict TypeScript + Vite production build (`npm run build`) | Passed |
| Bundled executable JAR (`backend/mvnw.cmd -Pwith-ui -DskipTests package`) | Passed after the frontend build |
| Real bundled reminder HTTP smoke | Authentication, settings, source types, snooze, dismissal, rescheduling and digest settings passed |
| Restart with file-backed H2 | Preferences, occurrence IDs and snooze times persisted |
| Independent read-only review of the fixes | No material remaining issue found in the three fixes |

Six added backend regressions cover account-timezone same-day delivery, sources created after the digest, future-event deferral, in-app delivery without email, overview while the user row is locked, and concurrent first-overview initialization. Four added client regressions exercise delayed application links, closing/reopening the reminder center, reversed refresh completion order, and normal successful navigation. The first three client cases failed before the navigation guard.

Validation used Java 21.0.12.1 (Java 17 source target), Node 25.2.0 and the Maven wrapper. HTTP/restart checks used a separate loopback backend, synthetic accounts and a temporary file-backed H2 database with email and the background worker disabled. The temporary backend was stopped and its database removed afterward. Scheduled delivery is covered by backend integration tests with a controlled clock and fake SMTP boundary.

PostgreSQL was unavailable: the lock change was reviewed against PostgreSQL foreign-key lock behavior, while contention and atomic initialization were tested on H2 in PostgreSQL mode. Browser visual checks were not repeated for these fixes. Python was unavailable, so the existing Node reminder HTTP helper performed the smoke/restart checks.

## Automated reminders validation — October 2, 2026

Implemented on `Autonamted-Reminders-Feature`. Validated with Java 21.0.12.1 (Java 17 source target), Spring Boot 3.5.16, Maven wrapper and Node 25.2.0. Accounts and source records were synthetic. Browser/HTTP/restart tests used a separate loopback backend on port 18082 and a dedicated temporary H2 database; reminder mail was disabled. Delivery integration tests used a fake SMTP boundary.

| Check | Result |
| --- | --- |
| Complete Java suite and bundled JAR (`backend/mvnw.cmd -Pwith-ui verify`) | 60 passed, no failures/errors/skips |
| Client tests (`npm test`) | 21 passed |
| Strict TypeScript + Vite production build | Passed |
| Flyway V1/V2/V3 and Hibernate startup validation | Passed |
| Real bundled HTTP reminder smoke | Passed |
| Abrupt JVM restart with file-backed H2 | Preferences, occurrence IDs and snooze times persisted |
| Desktop and 390px mobile browser checks | Settings, fixed-offset timezone, snooze, dismissal and application links passed; no horizontal overflow |
| Reminder link for an application created outside the cached workspace | Correct application drawer opened after refresh |
| Independent read-only code review | Six delivery/timing findings corrected; navigation finding also corrected |

Backend coverage includes opt-in defaults, session/CSRF protection, version conflicts, ownership, malformed timezone/time/range rejection, sample and closed-status exclusion, all three source categories, date edits/deletion, persistent snooze/dismiss, duplicate occurrence prevention, parallel reads, independent mail claim exclusivity, expiry revalidation outside a 100-owner page, lease recovery, temporary/permanent/exhausted SMTP failures, empty digest days, late arrivals, retry-preserving preference edits, DST gap/overlap scheduling and multiple jobs for 101 same-day deadlines.

Review regressions reproduced empty-window late delivery, lost digest retry, stale expiry mail and the 100-item omission before fixes. Digest release now uses the actual zoned cutoff and records empty processing windows without sending empty mail. Compatible preference edits keep eligible retries. Every claimed mail job reconciles its owner, larger digests use multiple jobs, and acknowledgement obtains the same owner lock as settings/actions. Browser tests reproduced successful `UTC+03:00` rendering using the tested offset formatter; unsupported legacy browser timezone support has a labelled UTC fallback.

SMTP acceptance after a crash remains at-least-once, and in-flight mail cannot be recalled. Production provider credentials/delivery, PostgreSQL execution, load/retention policy and hosted multi-instance deployment were not verified. Reminder SMTP remains disabled until explicitly configured; see README.md. The review's lock-order finding was resolved by consistent owner/job/occurrence serialization; the exact original deadlock interleaving was not reproduced experimentally.

Screenshots and synthetic account/server metadata are under ignored `test-results/`. `node tests/reminder_http_smoke.mjs` expects a dedicated temporary backend at `http://127.0.0.1:18082`; optional `--resume` verifies saved state after restart and `--add-source` creates a synthetic second-tab application for browser verification. Never point this helper at a normal user database. The backend and browser test session were stopped after verification.

## Password lifecycle validation — October 2, 2026

Validated with Java 21.0.12.1, Node 25.2.0, Maven wrapper and Spring Boot 3.5.16. Tests use synthetic accounts, isolated temporary/file-backed or in-memory H2 databases, and a loopback SMTP sink; no normal application data or production mail was used.

| Check | Result |
| --- | --- |
| Client/API/password tests (`npm test`) | 15 passed |
| Strict TypeScript + Vite production build | Passed |
| Backend (`backend/mvnw.cmd -Pwith-ui verify`) | 40 passed; no failures/skips; bundled executable JAR built |
| Flyway V1/V2 and Hibernate schema validation | Passed during tests and real startup/restarts |
| Python bundled CRUD/password/SMTP/restart smoke | Passed |
| Node bundled HTTP/password/SMTP/restart smoke | Passed |
| Encrypted email queued before JVM termination, delivered after restart | Passed |
| Browser recovery and initiating-session password change | Passed with synthetic email links |
| Desktop/mobile visual inspection | Passed at 1264px and 390px; no horizontal overflow on completion |
| Keyboard return, invalid links, immediate fragment removal | Passed |
| Pending login followed by link navigation/dismissal | Passed; stale response ignored, sign-in remains enabled |
| Lost response after committed password change | Passed; controls disabled, no replay, new-password login works |
| Security review | All 37 changed source-like files reviewed; one quota availability finding fixed and regression verified |

Backend coverage includes generic unknown-email responses, CSRF, authenticated change initiation, wrong-password/account/session/purpose rejection, expiry boundaries, weak-password retry, atomic replay/concurrency handling, rollback, fresh credential versions during concurrent issuance, ordered session bindings, two-session revocation, encrypted payload/AAD checks, durable/concurrent limits, notification enqueueing, worker claim exclusivity, expired lease recovery, retry/exhaustion and terminal payload erasure. Missing accounts and failed version lookups cannot authorize API requests.

The security scan's original schema-invalid draft submission did not create a manifest. Corrected submission and finalization completed the same scan, `6a0c7348-441b-4eba-829f-01db6a7c6609`. Its sealed pre-fix snapshot retains a medium finding: unknown/throttled requests consumed the shared delivery quota. The subsequent fix reserves global capacity only for eligible deliveries; a regression first failed, then passed. Independent review approved the correction. The sealed report is an immutable pre-fix observation, not a clean rescan of later corrections. Later token-memory cleanup and H2 durability changes received targeted tests/review.

Abrupt Windows JVM termination initially exposed H2's delayed-write window: recently acknowledged challenge/outbox rows disappeared. Local H2 URLs now use `WRITE_DELAY=0`; both real restart tests passed afterward. The SMTP test helpers decode the declared MIME encoding rather than interpreting unencoded link characters as quoted-printable escapes.

Frontend tests also verify that the initial module-held token is released when a flow is dismissed. Browser screenshots are under ignored `test-results/`: `password-recovery-desktop.png`, `password-completion-mobile.png`, `password-security-mobile.png`, `password-uncertain-mobile.png`, and `password-invalid-desktop.png`.

Not verified: PostgreSQL migration/concurrency, production SMTP/provider credentials, high-volume load, multi-instance shared sessions, cross-tab synchronization or hosted deployment. PostgreSQL/Docker tools were unavailable. Mail remains disabled until the environment variables documented in README.md are supplied. In-memory sessions require sticky routing or a shared session store for multiple instances; already authorized requests are not cancelled by credential-version changes.

Reproduce from the root with `npm test`, `npm run build`, and from `backend/` with `.\mvnw.cmd -Pwith-ui verify`. Then run `node tests/password_http_smoke.mjs` and `python tests/http_smoke.py`. Use `--serve` on the Node helper only for isolated browser verification; its test links and credentials are written to ignored `test-results/`.

## Previous validation — September 15, 2026

Validated September 15, 2026, using OpenJDK 17.0.20, Maven 3.9.11, Node 24.19.0 and Spring Boot 3.5.16.

| Check | Result |
| --- | --- |
| TypeScript + Vite production build | Passed |
| Client/API and selector tests | 8 passed |
| Spring Boot integration tests against H2 | 10 passed; no failures or skipped tests |
| Flyway initial schema + Hibernate validation | Passed during integration tests and real application startup |
| Executable JAR with bundled React assets | Built successfully; bundled files match the frontend build |
| Real HTTP smoke test | Passed |
| File-backed H2 persistence after stopping and restarting the JVM | Passed |

Java tests cover registration, password hashing, incorrect credentials, real CSRF tokens, authenticated CRUD, per-user isolation, optional sample data, request validation, stage updates/removal, pagination/search, missing version headers, stale writes, soft delete and undo. Timestamp precision is normalized to database microseconds so creation timestamps remain stable across round-trips.

The HTTP smoke test launches the executable JAR on loopback using a temporary database and synthetic account. It checks the website and static assets, registers and signs in, creates/updates/deletes/restores a record, rejects a stale update, restarts the JVM, signs in again, verifies the saved record and checks logout. It does not alter user data.

The test-only Mockito configuration uses the subclass mock maker, avoiding JVM agent attachment in restricted environments. The integration tests use real application services, security, controllers, migrations and database operations.

Not verified in the September 15 run: automated browser/visual QA, the PostgreSQL/Docker configuration, high-volume load behavior, or a hosted Java deployment. The earlier Sites deployment remains the separate local-data prototype.

Run `npm test`, `npm run build`, and `backend/mvnw -Pwith-ui verify` (on Windows use `mvnw.cmd`). After building the bundled JAR, `python tests/http_smoke.py` repeats the HTTP/restart check using only Python's standard library.
