# Validation record

## Main review fixes - October 4, 2026

Fixed the four review findings on local `main`: verification counter lock contention/deadlock, verification requests bypassing the shared authentication work cap, stale application responses expiring a newer session, and concurrent duplicate registration returning a conflict instead of acceptance.

| Check | Result |
| --- | --- |
| `npm test` | 40 passed; no failures or skips |
| `npm run build` | TypeScript and Vite build passed |
| Maven `-Pwith-ui,postgres-it -Dpostgres.test.bin=... verify` | 65 backend tests and 2 PostgreSQL integration scenarios passed; bundled JAR built |
| `npm run test:smoke` | Passed HTTP/assets, SMTP capture, email verification, authentication, preferences, CRUD, CSRF, conflicts, delete/undo, logout, and H2 restart persistence |
| Independent review and `git diff --check` | No important unresolved review issues; whitespace check passed |

Regression tests reproduced the reported failures before fixes. Coverage includes a locked counter bucket not blocking unrelated requests, concurrent bucket insertion and insertion between update/read, exact concurrent quota enforcement, verification work saturation and permit recovery, and duplicate signup acceptance with one complete account/sample dataset/token/outbox entry. Session tests cover old list/save/delete/undo responses, requests started during login, all pages retaining their original session boundary, and current-session expiration still being reported.

The fresh and legacy PostgreSQL 17.6 scenarios exercise concurrent quota enforcement and duplicate registration using the restricted runtime role, along with existing migration/privilege/HTTP checks. Counter statements commit independently of caller transactions; signup collision recovery reads the winning account only after the losing transaction rolls back. Checks used cached Maven 3.9.11, disposable databases, and synthetic accounts. No user database was migrated. Hosted deployment, browser visual verification, and external SMTP delivery were not run.

## Combined branch integration — October 4, 2026

Integrated `security/auth-db-hardening` and the shared tip of `email-verification-flow` / `feature/dark-mode` into local `main`. Excluded `Autonamted-Reminders-Feature`, `design/job-posting-contact-details`, and `password-recovery-feature`.

| Check | Result |
| --- | --- |
| `npm test` | 34 passed; no failures or skips |
| `npm run build` | TypeScript and Vite build passed |
| Maven `-Pwith-ui package` | 59 backend tests passed; bundled JAR built |
| PostgreSQL privilege integration tests | 2 passed against disposable PostgreSQL 17.6 clusters; no failures or skips |
| `npm run test:smoke` and `python tests/http_smoke.py` | Both passed HTTP/assets, local SMTP capture, verification, authentication, preferences, CRUD, CSRF, conflicts, delete/undo, logout, and H2 restart persistence |
| Git conflict and whitespace checks | Passed |

Verification used Node 24.11.1, Java 20.0.2, and cached Maven 3.9.11. The Windows Maven wrapper failed before launching Maven because it indexes an empty directory `Target` property; the cached `mvn.cmd` was invoked directly. PostgreSQL checks used the `postgres-it` profile with `postgres.test.bin` pointing to ignored binaries under `backend/target/merge-postgres/bin`, followed by `failsafe:integration-test failsafe:verify` after compilation and packaging. Tests used isolated databases and synthetic accounts; no user database was migrated.

Merge compatibility fixes preserve both authentication limiters with distinct bean names, the registration work cap, verification error codes, Retry-After handling, and stale-session protection. Runtime account updates are restricted to appearance and verification columns; Hibernate writes only changed columns. Legacy upgrades revoke runtime table access until migrations create the current schema and reapply grants. Both smoke runners use synchronous writes in their temporary H2 database because Windows process termination is abrupt.

Docker image/Compose execution, browser visual checks, hosted deployment, and external SMTP delivery were not performed. Earlier verification records below describe their respective branch snapshots.

Email verification was validated September 27, 2026, using Java 21.0.12.1, Maven 3.9.11, Node 25.2.0, and Spring Boot 3.5.16. The earlier September 23 record covered the dark-mode update.

| Check | Result |
| --- | --- |
| TypeScript + Vite production build | Passed |
| Client/API, verification-link, theme-state, and palette tests | 31 passed |
| Spring Boot integration, mail, rate-limit, and migration tests against H2 | 41 passed; no failures or skipped tests |
| Flyway V1/V2-to-V3 account upgrade + Hibernate validation | Passed; pre-V3 accounts are exempt and later accounts require verification |
| Executable JAR with bundled React assets | Built successfully; 5 frontend resources copied into the JAR |
| Real HTTP and SMTP-capture smoke test | Passed, including pending-account denial, email confirmation, CSRF, CRUD, and logout |
| File-backed H2 persistence after stopping and restarting the JVM | Passed; verified account, Dark preference, and application record survived |

Backend coverage includes password-first verification denial, generic resend and duplicate-registration responses, rejection of client-supplied exemption state, one-time/CSRF/password-protected confirmation, concurrent confirmation, persistent bad-password rate limiting, and existing-account migration behavior. Mail tests cover AES-GCM round trip and tamper rejection, transactional enqueue, eligibility checks, payload erasure, transient retry, permanent failure, retry exhaustion, lease ownership/takeover, terminal cleanup, and expired rate-limit cleanup. Sensitive authentication DTOs have redacted string representations so framework debug logging does not print email, password, or token values.

The HTTP smoke test launches the executable JAR on loopback with a temporary H2 database and an in-process SMTP capture server. It checks the website and static assets, registers an account, confirms that login is denied before verification, extracts the delivered token, confirms with the signup password, signs in, saves Dark appearance, rejects invalid and CSRF-free preference writes, creates/updates/deletes/restores a record, rejects a stale update, restarts the JVM, signs in again, verifies both the record and appearance, and checks logout. It does not touch user data or contact an external mail provider.

PostgreSQL and Docker Compose were not run because Docker is not installed in this environment. The V3 SQL is exercised in H2 PostgreSQL mode, and the Compose configuration was source-reviewed, but those checks do not establish PostgreSQL runtime compatibility. Browser automation was also unavailable: the Vercel `agent-browser` executable was absent and no browser surface was attached. The packaged page and all referenced JavaScript/CSS assets were fetched successfully through real HTTP, but this is not a visual or console-error check. No hosted deployment or external SMTP delivery was performed.

Run the current checks with:

```sh
npm test
npm run build

# From backend/ (use .\mvnw.cmd on Windows)
./mvnw test
./mvnw -Pwith-ui clean package

# Back at the repository root
npm run test:smoke
```

The Node smoke runner uses only built-in modules and required project runtimes. The older Python runner remains available for environments that already use it.

## Security implementation verification â€” September 17, 2026

Implemented authentication IP/account budgets, bounded anonymous CSRF-session allocation, a shared authentication work cap, 429/Retry-After client handling, and PostgreSQL provisioning/migration/runtime role separation.

| Check | Result |
| --- | --- |
| Backend unit and H2 security/workflow integration tests | 31 passed; zero failures/errors/skips |
| PostgreSQL privilege integration tests | 2 passed against isolated PostgreSQL 17.6 clusters; zero failures/errors/skips |
| Client tests | 12 passed |
| TypeScript/Vite production build | Passed |
| Executable JAR with bundled UI | Built during Maven verify |
| Real HTTP/H2 restart smoke test | Passed, including confirmed JVM shutdown |
| Independent source review | Confirmed anonymous unsafe-request session bypass; fixed and scoped re-review found no concrete regression |
| Docker image build, Compose configuration execution and migration-failure startup gating | Not executed: Docker unavailable |
| Production load/capacity validation | Not executed; limits are initial single-instance defaults |

Commands executed from the repository root:

```powershell
npm test
npm run build
python tests/http_smoke.py
```

Combined Maven verification executed from backend with locally extracted PostgreSQL test binaries:

```powershell
.\mvnw.cmd -B '-Ppostgres-it,with-ui' '-Dpostgres.test.bin=C:/Users/jeffr/Internship-Tracker-Demo/backend/target/postgres-verification/edb/pgsql/bin' verify
```

The postgres-it profile normally uses Docker/Testcontainers. Its optional Windows binary mode initializes its own databases under backend/target and cannot target an existing application database. Downloaded binaries and synthetic test clusters are ignored build outputs, not repository dependencies.

Both PostgreSQL scenarios exercised exact provisioning/upgrade scripts, repeat migrations, quoted test passwords, retained legacy data, effective role attributes/ownership, allowed data operations, and SQLSTATE 42501 for prohibited administrative operations. Each also started Spring Boot under the production profile with only runtime credentials and exercised real HTTP registration with samples, login, listing, creation, updates, stage removal, soft-delete/restore, and logout.

Regression evidence: missing authentication guards initially returned 401/201 where 429 was expected; client tests initially lost Retry-After and misreported CSRF throttling. A later anonymous unsafe-path test initially returned 403 and created a session beyond the intended budget; it now returns 429 without a session. Real PostgreSQL tests caught an unsupported Flyway logger setting, which was corrected before both scenarios passed.

The Windows PostgreSQL harness now redirects subprocess output to files and uses a timeout. The HTTP smoke test now resolves the actual Java runtime instead of terminating only Oracle's PATH launcher; it verifies the listening port closes before restart.

Security status: implementation and local verification complete. Docker rollout verification remains outstanding. No user database upgrade, public deployment, or remote push was performed. Existing PostgreSQL installations require the explicit procedure in docs/DATABASE-SECURITY.md.

## Password reset verification - October 12, 2026

Environment: Node25.2.0, Java21.0.12.1 compiling the Java17 target, cached Maven3.9.11, Spring Boot3.5.16, Vite8.0.16, disposable PostgreSQL18.6 clusters.

| Check | Result |
| --- | --- |
| Frontend/API and mounted UI tests | 61 passed; no failures/skips |
| TypeScript and Vite build | Passed |
| Backend unit/H2 integration suite | 112 passed; no failures/errors/skips |
| PostgreSQL fresh/legacy/V3 upgrades, restricted roles, HTTP recovery and lock-sensitive expiry | 3 passed; no failures/errors/skips |
| Wrapped socket-timeout and saturated pre-body admission regression | 2 passed in final packaging check |
| Bundled real HTTP and loopback SMTP capture | Passed reset link/notice, stalled body408, single use, session revocation, old/new password login, data/theme preservation and H2 JVM-restart persistence |
| Dependency audit after patched Vite/source-map-js updates | Zero reported vulnerabilities |
| Independent source review | Four P2 findings addressed; category/file references in docs/PASSWORD-RESET.md |

The startup test uses fresh JVMs with valid and unreadable supplemental blocklists. Other regressions cover post-commit database failure, completion rollback, concurrent single use, cutoff/primary-clock expiry, stale-cookie protection, mail-purpose/key tampering, suppression budgets, disabled dispatch, notification failure and recovery health pause. The mounted jsdom regression verifies focus during password entry/pending confirmation and preserves uncertain and successful completion results.

Commands: `npm.cmd test`, `npm.cmd run build`, `npm.cmd audit --json`, cached Maven `test`, `-Pwith-ui,postgres-it '-Dpostgres.test.bin=C:/Program Files/PostgreSQL/18/bin' verify`, final `-Pwith-ui -Dtest=ResetInputFilterTest package`, and `npm.cmd run test:smoke`. Maven used explicit local repository/user-home arguments because the wrapper/cache paths were inaccessible inside the sandbox; authorized elevated execution ran the installed cached Maven. All data and SMTP recipients were synthetic. Logs and test clusters remain ignored build outputs.

Limits: PostgreSQL17, Docker/Compose rollout, actual browser rendering and supported mail-client fragment handling, hosted/external SMTP delivery, production load/SLO monitoring, and backup/failover/restore exercises were not run. CUA exposed no browser surface; jsdom is mounted DOM verification, not a browser visual check. Flyway warns that PostgreSQL18 exceeds its tested17 support range; passing local18 tests does not replace testing the deployed database version. Recovery is disabled in production pending the runbook release gates. No user database upgrade, public deployment or remote push was performed.

Additional regression evidence: temporarily restoring the prior focus handler made the mounted test fail with both password fields cleared; restoring the fix passed7/7. The bundled smoke initially reproduced400 instead of408 for Tomcat's wrapped socket timeout; normalization plus the wrapped-exception unit test and final bundled smoke passed.

## Parallel design review and admission follow-up - October 15, 2026

Three parallel GPT-6.1 Sol High agents completed research-backed reviews of recovery practices, maintainability and security against the current design and selected implementation. Recovery and maintainability reviews found no new concrete defect in scope. Security found one low-severity issue: health and maintenance fallback writes could bypass database-work admission when their initial permit acquisition failed. Both tasks now defer on saturation and acquire a new permit before any fallback JDBC update; the security reviewer confirmed the fix by inspecting the diff.

Four parameterized cases in `ResetBackgroundAdmissionTest` reproduced the defect before the fix, then passed. They verify no JDBC work under saturation, a held permit during the fallback update and permit release afterward. The targeted run passed19 tests including recovery integration; the full backend suite passed116 tests with zero failures/errors/skips and the bundled executable JAR packaged successfully. Commands used cached Maven with the existing explicit local repository/user-home arguments: `-Dtest=ResetBackgroundAdmissionTest test` (red), `-Dtest=ResetBackgroundAdmissionTest,PasswordResetIntegrationTest test` (green), and `-Pwith-ui package` (full).

Source references and research links are recorded by category in `docs/PASSWORD-RESET.md`. Frontend and PostgreSQL suites were not repeated because this follow-up changes only background Java admission/failure handling; their October12 results above are historical evidence, not new runs. Production deployment, provider/browser checks, load/monitoring and restore/failover gates remain unchanged.

The final bundled `npm.cmd run test:smoke` passed on October15 using disposable file-backed H2 and local SMTP capture: reset link/notice, slow-body408, single use, session revocation, old/new login, preserved data/theme, and JVM-restart persistence. No user database or external mailbox was used.

## Repeated review and fixes - October 29, 2026

Continued `Password-Reset` from `1d8d528` in the current Internship Tracker workspace. Independent GPT-6.1 Sol High reviews covered backend/session security, recovery UI and operations. The first pass found shared-session invalidation races, session-check failures incorrectly treated as anonymous, and overdue issued links missing from the health pause. The second security pass found that an older rejected login could overwrite a newer login before the success-handler fence. Final independent reviews of the corrected files found no remaining actionable findings in those scopes.

Regression evidence: delayed API and login rejection tests first invalidated the replacement session; the BCrypt race first returned 401 for the newer login's next request; two overdue-mail cases first failed to pause intake. The first login persistence configuration failed two regressions because Spring replaced the fluent repository setting. Configuring the built login filter through `ObjectPostProcessor` fixed that override. Login now checks the epoch and publishes authentication under one session mutex, while passive rejection and reset completion clear only request-local context. Existing API epoch checks enforce logical session revocation.

| Fresh check | Result |
| --- | --- |
| Frontend/API and mounted recovery UI tests | 61 passed; no failures/skips |
| TypeScript and Vite production build | Passed |
| Full backend unit/H2 integration suite | 129 passed; no failures/errors/skips |
| PostgreSQL 18.6 integration scenarios | 3 passed; no failures/errors/skips |
| Bundled executable JAR | Packaged successfully with current frontend assets |
| Bundled HTTP/loopback SMTP smoke | Passed reset link/notice, slow-body deadline, single use, session revocation, old/new credentials, data/theme preservation and H2 JVM-restart persistence |
| Independent final review | No remaining actionable findings in frontend, operations or security/concurrency scopes |

The PostgreSQL tests include the new fresh/overdue/drained link-health assertions using the restricted runtime role, as well as existing migration/privilege, real HTTP recovery and lock-sensitive expiry coverage. H2 regressions also cover pending/sending mail, consumed/revoked/expired or mismatched links, deferred notices, session races and a primary-read outage preserving another account's session.

Commands: `npm.cmd test`, `npm.cmd run build`, focused Maven regression runs, cached Maven `-Pwith-ui,postgres-it '-Dpostgres.test.bin=C:/Program Files/PostgreSQL/18/bin' verify`, and `npm.cmd run test:smoke`. Maven used the existing explicit local-repository/user-home arguments with authorized cache access. Build/test logs and disposable database clusters are ignored outputs; the smoke test removed its own temporary H2 database and used only a loopback SMTP capture server.

Production activation remains gated by the runbook. PostgreSQL 17, Docker rollout, real browser/mail-client behavior, external SMTP delivery, production load/monitoring and restore/failover drills were not established by these local checks. Flyway still warns that PostgreSQL 18 is beyond its tested 17 support range. No user database, external mailbox or production deployment was changed.

## Production readiness checks - October 29, 2026

The [readiness report](docs/PASSWORD-RESET-READINESS.md) records fresh evidence and the remaining hosted gates. This pass closes the PostgreSQL 17, real-browser and isolated restore gaps above: 64 frontend tests, TypeScript/Vite build, final 129-test backend suite, five deployment-probe regressions, three PostgreSQL 17 migration/privilege scenarios, one restricted monitoring scenario and one backup/restore/reconciliation scenario passed. PostgreSQL checks ran in separate disposable 17.11 clusters; the corrected restore rerun passed independently. The rebuilt HTTP/SMTP smoke passed and the real in-app browser verified mobile layout, fragment scrubbing, refresh/dismissal, explicit sign-out and cross-tab transitions.

Fixes include Docker environment-file exclusions and frontend test execution, token-free refresh guidance, a schema-valid deterministic expired-token fixture, and PostgreSQL 17-compatible restore-script failure guards. A tiny H2/loopback capacity probe passed generic intake, throttling/Retry-After, notice delivery and log-redaction checks; its measured latencies are recorded with sample counts and do not establish production SLOs. Transport probes measured a five-second stall cutoff and approximately nine-second late-byte cutoff, confirming the documented need for an edge total deadline.

The restore drill first demonstrates old hashes and a spent active link returning in a pre-reset backup while the restored database is offline. Guard failures leave state untouched. Reconciliation blocks all restored passwords, revokes verification/reset challenges, erases queued payloads, advances ordering/epochs and preserves audit/counters; controlled fresh mailbox recovery then succeeds while old credentials and links remain rejected. Clean PostgreSQL restart persistence passed; managed crash/failover remains separate. Independent cross-reviews found no additional actionable restore or monitoring defects.

Commands included cached Maven `-Pwith-ui,postgres-it -Dtest=PasswordResetIntegrationTest -Dpostgres.test.bin=<portable-PG17-bin> verify`, the targeted corrected `-Dit.test=PasswordResetRestoreIT` rerun, final Maven `test`, `npm.cmd test`, `npm.cmd run build`, `node --test deploy/probe-edge.test.mjs`, `node tests/reset-readiness-load.mjs`, and `node tests/http_smoke.mjs --hold-browser`. The first full runs exposed the unstable current-instant expiry fixture and the PostgreSQL 17 guard exit behavior; final runs passed after correction. The load/probe harnesses also caught and corrected their own cache-directive/timing assertions.

Docker/Compose execution requires a Docker host. HTTPS/proxy/Secure-cookie checks, external SMTP/domain/client delivery, sustained production-equivalent load, live dashboards/on-call alerts, durable audit export and managed failover require the staging target and operator/provider access. None is marked passed. Recovery remains disabled pending those hosted checks and release approval.

## Free local launcher - October 29, 2026

`start-local.ps1` supplies a Docker-free Windows alternative: the bundled Java app, a separate persistent H2 database and a pinned, checksum-verified Mailpit capture inbox. `-PrepareOnly` successfully reported Mailpit 1.31.1. A real launch on isolated ports 18080/18025/11025 with data under `backend/target/local-launcher-check` passed app/inbox health and confirmed all listeners bind to 127.0.0.1. Synthetic registration, verification, reset email capture, password completion, old-password rejection and new-password login passed through HTTP and Mailpit's API. Ctrl+C stopped all three listeners while preserving the H2 and inbox files. Independent review caught case-sensitive matching of inherited Mailpit settings; matching is now case-insensitive. Restart with an invalid lowercase forwarding setting still started the capture-only inbox, preserved all three messages and accepted the saved reset password. Duplicate ports were rejected. PowerShell parsing and `git diff --check` passed. No application runtime code changed; the full frontend/backend suites were not repeated for this launcher/documentation addition. See [local setup](docs/LOCAL-PASSWORD-RESET.md).
