# Validation record

## CSV import - October 15, 2026

Implemented the approved CSV import workflow: selection/mapping/correction/exclusion, complete before/after review and update acknowledgement, immutable owner-bound previews with safe supersession, atomic application changes and durable receipts, status-only automatic recovery, retention/cleanup and persisted admission quotas. Application ID/structured-stage exports and import reports share spreadsheet-risk reduction. V4 and explicit runtime grants cover the new metadata tables. See the [API contract](docs/API.md#csv-imports), [implementation adjustments](docs/superpowers/specs/2026-10-15-csv-import-design.md#9-implementation-adjustments-and-verification-boundaries), and [consolidated independent review](docs/superpowers/reviews/2026-10-15-csv-import-implementation-review.md).

| Final check | Result |
| --- | --- |
| `npm.cmd test` | 68 passed; no failures or skips, including 12 real React/JSDOM component/hook tests |
| `npm.cmd run build` | Strict TypeScript and production Vite build passed |
| Cached Maven 3.9.11, `-Pwith-ui,postgres-it '-Dpostgres.test.bin=C:/Program Files/PostgreSQL/18/bin' -B clean verify` from `backend/` | 129 backend tests and 2 PostgreSQL integration scenarios passed; no failures/errors/skips; rebuilt UI bundled in the JAR |
| `node tests/http_smoke.mjs` against the final rebuilt JAR | Passed HTTP/assets, SMTP-capture verification/authentication, preferences/CRUD/undo, real multipart CSV Add/Update/preservation, native Node FormData through bundled serving and Vite proxy, CSRF, atomic conflicts, duplicate receipts, discarded-response GET reconciliation and application/import persistence through a full JVM restart |
| Three independent reviews and focused fix rechecks | Completed; both P2 findings and the P3 finding fixed, with regression tests; no outstanding actionable finding |
| `git diff --check` | Passed |

Backend coverage includes full-source parsing/limits, strict options/stages, preservation/explicit clearing, advisory versus explicit owner-scoped matching, validation and ownership, atomic rollback including an injected second-write failure, stale ordinary/stage edits, duplicate same-ID and different-preview target races, repeated correction supersession, synchronized expiry/reservations, consistent quota races, 500-row Add/Update/Unchanged receipts, canonical integrity/schema rejection, and locked 30-day receipt purge. Real-controller socket tests cover encoded route aliases, missing/blank/invalid CSRF with zero parameter/input/servlet-part access, unsupported transport before admission, wrong-endpoint multipart/error dispatches, same-owner capacity, stalled/dripping bodies, and headers with zero body bytes. The latter exposed decoder retries after a failed socket read; retaining the first I/O failure now prevents another underlying read, with capacity available at six seconds and terminal 408 below eight seconds. The original 30-second elapsed upload deadline/35-second recovery bound remains covered.

The PostgreSQL scenarios use fresh and legacy security-upgrade clusters, exercise V3-to-V4 migration and idempotent grants, and run HTTP imports with the restricted runtime role. Checks include supersession, update/status/duplicate commits, target lock cancellation and retry, foreign/deleted targets, locked expiry/cleanup, concurrent admission and receipt reservations, and 500-row batches. They also retain existing denied user/application deletion, schema/role/Flyway-history privilege checks. No user database was migrated.

Frontend regression coverage includes exact update acknowledgement, literal uploaded markup, error descriptions and cross-page focus, draft/replacement invalidation, lost/malformed responses, read-only reconciliation without automatic POST replay, unresolved mutation locking, full-history refresh, stale accounts/session guards and receipt reopening after 404/410 retirement. JSDOM deliberately shims native dialog and scrolling APIs; these checks do not establish browser modal behavior.

The three reviewers worked independently using GPT-6.1 Sol / High. All reports finished before fixes began; each reviewer then rechecked the changes in their scope. Backend review also identified four test-coverage gaps, addressed by five additional passing lifecycle tests. Security review found the encoded-route admission discrepancy; frontend review found stale retirement hiding new receipts and unresolved replacement previews retaining confirmation. A further real-socket regression found the failed-read retry issue, independently rechecked by the security reviewer.

Checks used Node 25.2.0, Java 21.0.12.1 (Java 17 compilation target), cached Maven 3.9.11 and disposable PostgreSQL 18.6. The Windows Maven wrapper still fails before launch (`Cannot index into a null array`); cached Maven was invoked directly with an explicit workspace-local repository under ignored `.superpowers/sdd/2026-10-15-csv-import/m2`. Restricted javac ZipFS access required approved execution outside the sandbox. `npm.ps1` is blocked by execution policy, so `npm.cmd` was used. The maintained Node HTTP smoke runner is used because the Python launcher is unavailable.

Actual browser visual/reflow/theme checks, native modal Tab/Shift+Tab/Escape behavior and screen-reader announcements remain **blocked**: computer-use inventory had no browsers, and both in-app and Chrome launch attempts returned unavailable. Native Node FormData and JSDOM are separate evidence, not browser verification. PostgreSQL 17 and a Java 17 runtime were not tested; Flyway warns that PostgreSQL 18 is newer than its tested support range. Hosted proxy/deployment behavior and external SMTP delivery were not verified.

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

The Node smoke runner uses built-in Node modules and dynamically imports the project's Vite development dependency for proxy checks. Install development dependencies with `npm ci` (or explicitly `npm ci --include=dev`) before running it. The older Python runner remains available for environments that already use it.

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

## Running app + actual Mailpit end-to-end check - October 29, 2026

Tested the already running local app at 127.0.0.1:8080 and Mailpit at 127.0.0.1:8025. A UUID-named synthetic account was registered and activated through its actual captured verification message. The real in-app browser submitted Forgot password, displayed neutral acceptance, showed the reset email in Mailpit and opened its link; the URL fragment was scrubbed and empty required fields blocked submission. Password submission and negative checks used independent HTTP clients. Seven check groups passed: verification/two sessions, captured reset/private unknown-address behavior, page GET and rejected CSRF/short-password submissions preserving authentication, successful confirmation/old-session revocation, used/unknown token rejection, old-password failure/new-password login with application/theme preservation, and a credential-free password-change notification in Mailpit.

The repeatable unattended command `node tests/local-mailpit-smoke.mjs` also passed all seven groups, including exact known/unknown response comparison. Interactive browser-request mode was run separately; it does not itself compare the browser response body. The script requires a TTY before creating fixtures if an inspection pause is requested. An initial headless pause was stopped and rerun in a terminal. No application defect was reproduced. Independent harness review found no actionable safety/data-scoping problem and clarified HTTP-vs-browser evidence wording. Syntax and whitespace checks passed. Email-link origin validation was checked against both captured message types and rejected an incorrect origin. After testing, the local launcher was restarted to clear the process-local signup/login budgets consumed by fixtures; health and all eight captured messages persisted. Both local services remain available; only identifiable synthetic test records/messages were added.

## CSV import review fixes — 2026-10-20

Fixed the confirmation resource-control gap and the runtime privilege verifier's any-versus-all DML checks from the review of `2b52a702bfcdae251834b22246689ec62e0ff104`.

Confirmation POSTs now share the existing owner/process write permits with previews. Authentication and CSRF precede admission and body reads. A bounded incremental reader checks the existing absolute deadline between underlying socket reads; fixed-length HTTP/1.1, the 4096-byte confirmation ceiling, and strict JSON remain enforced. Status reads remain outside work permits. Persisted reservations and commit/receipt transactions were not changed.

Regression evidence before the fix: a continuously dripping confirmation produced no response within the test's 4.5-second observation window despite its configured 1.5-second deadline; a same-owner confirmation reached validation (HTTP 400) while another body was pending instead of returning 429. After the fix, raw-socket tests return 408 for a continuous drip, release capacity after idle timeout/disconnect, reject owner/process excess work, and permit status reads. Tests also cover decoded route aliases, body-free authentication/CSRF/transport/size rejection, malformed JSON, a valid exact-4096-byte confirmation, and duplicate receipts. The test tightens existing configuration rather than changing production defaults.

The PostgreSQL regression independently removes each of 35 required table/column grants, invokes the real `psql` verifier, and restores the grant in `finally`. Before the fix, 29 missing grants were incorrectly accepted. After the fix, every omission fails verification; the provisioned and restored roles pass. Prohibited-privilege checks and grants are unchanged.

| Check | Result |
| --- | --- |
| Backend suite | 136 passed, no failures/errors/skips; includes 7 new real HTTP confirmation tests |
| Real PostgreSQL integration | 3 passed: fresh provisioning, legacy upgrade, and 35 independent grant-removal cases |
| Frontend suite | 68 passed |
| TypeScript/Vite build | Passed |
| Executable JAR with rebuilt UI | Packaged successfully after the frontend build |
| Bundled real HTTP/SMTP/H2 restart smoke | Passed, including assets, verification, authentication, CSRF, conflicts, undo and restart persistence |
| Parallel independent reviews | Request/resource controls and PostgreSQL verifier: no actionable findings |
| Patch whitespace check | `git diff --check` passed |

Commands used (`taskMaven` is the cached Maven 3.9.11 executable):

```powershell
# Repository root
npm.cmd test
npm.cmd run build
# backend/
$taskMaven = 'C:/Users/jeffr/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $taskMaven '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' test
& $taskMaven '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' '-Dtest=PostgresPrivilegesIT' '-Dpostgres.test.bin=C:/Program Files/PostgreSQL/18/bin' test
& $taskMaven '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' '-Pwith-ui' '-DskipTests' package
# Repository root; installed Python executable avoids the WindowsApps alias
& 'C:/Users/jeffr/AppData/Local/Programs/DeepSeek Harness/resources/runtime/primary-runtime/dependencies/python/python.exe' tests/http_smoke.py
git diff --check
```

Environment adaptations: PowerShell blocks the `npm.ps1` shim, so `npm.cmd` was used. The Maven wrapper's Windows path resolution failed; the cached Maven executable with an explicit local repository was used. One compilation attempt reported a dependency-path error; the subsequent focused tests, full suite, and packaging succeeded. Local PostgreSQL launch required execution outside the restricted process sandbox; it initialized and stopped only disposable test clusters under `backend/target`.

Limits: PostgreSQL checks used installed PostgreSQL 18.6; Flyway emitted its existing warning that this version is newer than its tested support range. The Docker/PostgreSQL 17 path, production load tests, and browser UI QA were not run for this patch. The transaction permit-lifetime coverage gap was subsequently closed by the dedicated real-HTTP tests recorded below. No source-backed bypass or regression remained in either review.

## CSV import transaction permit lifetime — 2026-10-20

Added `ImportCommitPermitServerTest` against baseline `fbbcd589454321f30bb39c7adb172c5b29a9c91b`. The final patch changes only tests and documentation. Runtime admission, parsing, persistence, grants and transaction behavior are unchanged.

The test-only repository advisor proceeds with the real `ImportPreviewRepository.complete` SQL before registering transaction synchronization. In `beforeCommit`, each test verifies the active transaction is `ImportCommitService.commit`, observes the flushed application rows and pending COMPLETED receipt on the transaction-bound connection, then holds completion with a bounded latch. An outer HTTP observer proves the full advertised confirmation body was read and later signals after the security/admission filter has returned. An outside database snapshot simultaneously sees only the original PREVIEW and no new applications or receipt, establishing that real SQL ran but has not committed.

Both success and rollback scenarios use real authenticated, CSRF-protected HTTP confirmations and a test-only process limit of one. While the transaction is paused, valid same-owner and different-owner confirmations return 429 with exact `IMPORT_BUSY`; GET returns the original PREVIEW without a receipt. Success verifies the durable receipt, application IDs/versions, metadata cleanup, duplicate receipt and later writes by both owners. Rollback throws a controlled test-only `TEST_FORCED_ROLLBACK` ApiException from `beforeCommit`, verifies `STATUS_ROLLED_BACK`, exact original metadata/canonical payload and reservation preservation, no partial applications/receipt, then successfully retries the identical confirmation. Recovery probes await outer request completion, not merely transaction completion. Gates are released in finally blocks and outstanding response futures are cancelled on cleanup; no arbitrary sleeps or manually acquired work permits are used.

Mutation sensitivity was executed, not inferred: the controller was temporarily constructor-injected with the real work limiter and released its owner permit immediately after `confirmations.read(request)`. Both tests failed at the intended `assertBusy` assertion because a competing valid import returned a successful receipt while the original transaction was still paused. The mutant Maven run exited 1 with 2 assertion failures, 0 errors and 0 skips. A finally block restored the controller's exact original bytes; SHA256 before/after was `556E647F96EF2B0E4C32483E3F4DE5F96B4BDD91CCD4AB87234F3094AADB719B`. Production sources have no diff against the baseline after restoration.

| Check | Actual result |
| --- | --- |
| New test class before mutation | 2 passed, 0 failures/errors/skips |
| Deliberate early-release mutation | Both tests failed as intended; mutation removed |
| Restored new tests plus confirmation transport and lifecycle tests | 27 passed, 0 failures/errors/skips |
| Full backend suite after restoration | 138 passed, 0 failures/errors/skips |
| Independent parallel reviews | Transaction/test correctness and resource/compatibility: no actionable defects |
| Final patch whitespace check | `git diff --check` passed |

Execution used the documented cached Maven fallback, from `backend/`:

```powershell
$taskMaven = 'C:/Users/jeffr/.m2/wrapper/dists/apache-maven-3.9.11/03d7e36a140982eea48e22c1dcac01d8862b2550b2939e09a0809bbc5182a5bc/bin/mvn.cmd'
& $taskMaven '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' '-Dtest=ImportCommitPermitServerTest' test
& $taskMaven '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' '-Dtest=ImportCommitPermitServerTest,ImportConfirmationTransportServerTest,ImportLifecycleTest' test
& $taskMaven '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' test
# Repository root
git diff --check
```

Local execution evidence and the reversible mutation runner are ignored outputs under `backend/target/sdd/import-transaction-permit-tests/`: `early-release-mutation.log`, copied `mutation-*` JUnit reports, `focused-restored.log`, and `backend-suite.log`. These are disposable local evidence, not required runtime files.

Subagent work: one read-only boundary investigator, one exclusive test implementer, and two fresh independent parallel reviewers; all used GPT-6.1 Sol High. The parent independently checked code/results, ran the mutation and restored suites, and owned documentation/integration.

Review disposition: both reviewers noted that process capacity one makes the new class's same-owner rejection also a consequence of process saturation. This is complementary coverage rather than a new runtime defect: existing `ImportConfirmationTransportServerTest.decodedCommitAliasesShareOwnerAdmissionWithPreview` uses two process slots and holds only one while checking same-owner exclusion; it passed in the 27-test and full runs. The new tests specifically establish permit lifetime through actual commit and rollback, as requested.

Limits: the new transaction tests use isolated in-memory H2 and add no PostgreSQL-specific claim. PostgreSQL integration, frontend build/tests, bundled smoke and browser checks were not rerun for this test/documentation-only patch; their earlier recorded results above remain historical. No infrastructure blocked the requested checks, no production behavior change was needed, and no mutation remains.

## Continuous integration implementation — 2026-10-20

Added GitHub Actions application verification and container-build jobs with the exact-success `CI required` gate. Coverage, trust boundaries, timeouts, reproduction commands and the separate branch-protection recommendation are documented in [docs/CI.md](docs/CI.md). No branch-protection or other repository setting was changed.

The results below are new local evidence from Windows for this CI change. They are separate from the historical runs above. Execution used checksum-verified portable Temurin **17.0.20.1+1** and Node **22.23.3** with npm **10.9.9**, plus Maven **3.9.11** and actionlint **1.7.12**. Backend execution and the packaged-JAR smoke used the actual Java 17 runtime, rather than a newer JDK with a Java 17 compilation target.

| Check | Actual result |
| --- | --- |
| `npm ci --include=dev` | Passed after repairing two missing package-lock records; see installation note below |
| `npm test` | 68 passed, no failures or skips |
| `npm run build` | Strict TypeScript and production Vite build passed |
| Maven `clean test` on actual Java 17 | 138 passed, no failures/errors/skips; local Windows unit-suite runtime was approximately 2 minutes 32 seconds |
| Maven `-Pwith-ui -DskipTests package` after the successful test run | Passed against the same unchanged backend source; skipping applied only to this packaging step |
| Bundled frontend verification | All 5 files in the rebuilt `dist` were byte-identical to their copies in the executable JAR |
| `npm run test:smoke` on Node 22 / Java 17 | Passed, exit 0; real HTTP/assets, SMTP verification/authentication, preferences, CRUD/undo, multipart CSV Add/Update/preservation, native FormData through bundled serving and the Vite proxy, CSRF, atomic conflict, duplicate receipts, lost-response GET reconciliation, and application/import recovery across a JVM restart |
| CI guard tests | 16 passed, including real CLI exit codes, missing/failing/cancelled/skipped job results, invalid reports, and each missing PostgreSQL scenario |
| actionlint 1.7.12 | Passed |
| Bash syntax checks | All 7 multiline workflow Bash blocks passed `bash -n`; Maven wrapper and PostgreSQL provisioning-script syntax checks passed |
| Node smoke-runner syntax | `node --check tests/http_smoke.mjs` passed |
| Actual `postgres-it` Failsafe invocation | Failed as required, exit 1: 3 discovered tests, 3 errors, 0 skips because Docker is absent; this establishes failure propagation, not PostgreSQL correctness |
| CI report guard against those actual failed PostgreSQL reports | Rejected the reports with exit 1 |
| Local Compose validation and both image builds/runtime checks | Unavailable locally because Docker is absent; the hosted results are recorded separately below |
| Fresh independent reviews | CI correctness/reproducibility and security/resource reviews found no actionable findings; the security reviewer independently reran all 16 guard tests successfully |

The initial `npm ci` failed because `package-lock.json` omitted the required `@emnapi/core` and `@emnapi/runtime` **1.10.0** records. The lockfile repair adds only those two records and changes no dependency version. The subsequent `npm ci --include=dev`, tests and build all passed. npm audit also reported **2 existing high-severity advisories** involving `source-map-js` and `vite`; their versions remain unchanged. Resolving those advisories is outside this CI change.

The Maven commands used with the verified Java 17 runtime, from `backend/`, were:

```powershell
mvn -B -ntp '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' clean test
mvn -B -ntp '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' '-Pwith-ui' '-DskipTests' package
# Expected failure here: this machine has no Docker daemon.
mvn -B -ntp '-Dmaven.repo.local=C:/Users/jeffr/.m2/repository' '-Ppostgres-it' failsafe:integration-test failsafe:verify
```

The local 138-test backend result includes the two pre-existing, uncommitted `ImportCommitPermitServerTest` cases. The isolated CI-only publication excludes that test class and its plan while preserving the local work, so that published snapshot contains 136 backend tests, as confirmed by the hosted run below. This count difference reflects source scope; the workflow does not hard-code backend test counts.

The successful test run precedes the packaging-only skip above. CI itself does not skip tests: it uses one `sh ./mvnw -B -ntp -Pwith-ui,postgres-it clean verify` invocation, then requires valid executed test reports before running smoke.

Local Docker remains absent; the failed local PostgreSQL attempt above is separate from the successful hosted PostgreSQL/container execution below. Full Compose rollout and migration-failure startup gating are separate from the selected CI build checks and were not run. Browser visual/accessibility checks, hosted deployment and external SMTP delivery were not run. Local Windows timings do not measure hosted workflow runtime.

### Hosted verification

[GitHub Actions run 39469068417](https://github.com/Jeffreyw7/Internship-Tracker-Demo/actions/runs/39469068417) **succeeded** for CI source commit [`6e47873f0812b634cd0c6c446b3d91bc300ee705`](https://github.com/Jeffreyw7/Internship-Tracker-Demo/commit/6e47873f0812b634cd0c6c446b3d91bc300ee705) in [draft PR #1](https://github.com/Jeffreyw7/Internship-Tracker-Demo/pull/1), targeting `CSV-import`. It ran on Ubuntu 24.04 with Node 22 and actual Temurin **17.0.20.1**.

| Hosted check | Actual result |
| --- | --- |
| Frontend tests/build | 68 tests passed; production build passed |
| Backend unit suite | 136 passed, no failures/errors/skips |
| Real PostgreSQL 17 Testcontainers integration | All 3 scenarios passed against PostgreSQL **17.11**, no failures/errors/skips; 52.08 seconds |
| Required Maven report guard | Verified 136 backend tests and all 3 PostgreSQL scenarios |
| Bundled HTTP/SMTP/Vite/H2 restart smoke | Passed |
| Compose configuration | Passed |
| Both `migrate` and `app` image builds/runtime checks | Passed; both runtime users were non-root and Java 17 |
| `Application verification` / `Container builds` / `CI required` | All succeeded; respective job durations were **4 minutes 25 seconds**, **3 minutes 21 seconds**, and **6 seconds** |

The PostgreSQL scenarios exercised provisioning, legacy upgrade, runtime grants and import behavior. This run establishes the PostgreSQL 17 and container-build coverage that could not execute locally. It does not establish full Compose rollout or migration-failure startup gating.

A separate, existing Git Credential Manager credential supplied workflow publication permission; the GitHub CLI credential alone lacked that permission. The isolated CI publication used a temporary index, preserving the current branch/index and the pre-existing uncommitted regression test/plan. No image was published and no repository setting was changed. The hosted evidence above applies to the linked source commit; later documentation or formatting updates have their own run.

Final published revision `e4c23169fac13474d0ff84c93e7afe0ce3b1b44e` was independently rerun after the documentation/formatting update: [run 39470045165](https://github.com/Jeffreyw7/Internship-Tracker-Demo/actions/runs/39470045165) passed all three jobs. Application verification took 4m45s, container builds 3m23s, and CI required 6s. The final logs again confirm 136 backend tests plus all 3 PostgreSQL tests without skips, bundled smoke success and exact-success required results. The preceding hosted run remains valid evidence for the original CI snapshot; the latest run confirms the final PR head. Existing Spring Security AntPathRequestMatcher deprecation warnings remain non-failing and outside this CI change.

### Publication follow-up

The subsequent commit-and-push request includes the previously preserved `ImportCommitPermitServerTest` class and its plan, along with the CI design/plan/review records. The 136-test hosted results above apply to the linked earlier CI-only snapshots; the complete published source now includes the two locally verified regression cases. Its hosted backend suite is expected to contain 138 tests. The new head's CI result must be read from PR #1 checks; this publication note does not claim a new passing run.

## Password Reset final merge review — October 29, 2026

Reviewed the combined Password-Reset implementation and main `94b062c94638597c9c92e83fa141f8d98cfd3d43`, including main's CSV import and CI changes. Parallel GPT-6.1 Sol High reviews covered frontend integration, database compatibility and security. Independent follow-up reviews checked the local migration bridge and the corrected expiry test fixture. Review findings and resolutions by category:

- **Database compatibility:** main already assigns V4 to CSV import. Reset is now V5 with identical SQL/checksum. `LocalLegacyPasswordResetMigration` and `start-local.ps1` provide an explicitly enabled, local-only file-backed H2 bridge for the unpublished reset-only demo. It validates the exact old migration history and reset schema, creates an adjacent consistent ZIP backup before changing history, then applies the missing CSV migration. Production and ambiguous histories fail closed; fresh/main databases migrate normally. Six regression scenarios cover data/control preservation, actual backup restoration, rejection paths and repeat startup. See [migration strategy](backend/src/main/java/com/campus/tracker/migration/LocalLegacyPasswordResetMigration.java), [tests](backend/src/test/java/com/campus/tracker/migration/LocalLegacyPasswordResetMigrationTest.java), and [local recovery instructions](docs/LOCAL-PASSWORD-RESET.md).
- **Security:** the combined filter chain retains primary-database credential-epoch checks before import admission and CSRF, explicit authenticated-session publication and both recovery/import body guards. Import test sessions now represent real verified users with actual credential epochs; stale sessions cannot upload or read imports. Runtime grant verification covers both features, with separate retention-role upgrade paths from V3 and V4. See [security configuration](backend/src/main/java/com/campus/tracker/config/SecurityConfig.java), [transport regression tests](backend/src/test/java/com/campus/tracker/application/importing/ImportTransportServerTest.java), and [PostgreSQL verification](backend/src/test/java/com/campus/tracker/PostgresPrivilegesIT.java).
- **Frontend and maintainability:** resolved shared transport conflicts while preserving serialized authentication, cross-tab invalidation, stale-operation guards, unknown import outcomes and no automatic write replay. Combined HTTP smoke coverage retains CSV import and password-reset flows. See [shared transport](src/data/api.ts), [import tests](tests/application-import.test.mjs), and [HTTP smoke](tests/http_smoke.mjs).
- **CI and test reliability:** supplied the newly required synthetic retention password to Compose validation. CI now requires all seven PostgreSQL scenarios across privilege/upgrade, restore and monitoring suites instead of accepting only the older three-case privilege suite. The full backend run exposed an expiry fixture using separately sampled H2/JVM timestamps; its test-only controlled clock now checks exact equality while preserving the creation-before-expiry constraint. See [workflow](.github/workflows/ci.yml), [report guard](.github/scripts/check_ci.py), [guard regression tests](.github/scripts/test_check_ci.py), and [expiry integration test](backend/src/test/java/com/campus/tracker/PasswordResetIntegrationTest.java).

Final verification of the resolved combined tree:

| Check | Result |
| --- | --- |
| `npm.cmd test` | 92 passed, no skips |
| `npm.cmd run build` | TypeScript and Vite production build passed |
| Maven `-Pwith-ui,postgres-it -Dpostgres.test.bin=<portable-PG17-bin> verify` | 208 backend tests and 7 PostgreSQL integration tests passed, no failures/errors/skips; packaged JAR built |
| `python -B .github/scripts/check_ci.py reports backend` | Confirmed all 208 backend tests and all 7 required PostgreSQL scenarios executed without skips |
| `python -B -m unittest discover -s .github/scripts -p 'test_*.py'` | 17 passed |
| `node --test deploy/probe-edge.test.mjs` | 5 passed |
| `npm.cmd run test:smoke` | Passed combined real HTTP/assets, SMTP verification/reset/notice, slow-body deadline, session revocation, token single use, old/new login, data/theme preservation, CRUD/undo, CSV multipart/native FormData through bundled/Vite routes, CSRF, atomic conflict, duplicate receipts, lost-response reconciliation, and JVM restart persistence |
| `git diff --cached --check` | Passed after resolving every conflict |

Actual local demo upgrade: the running app created `backend/data/local-password-reset/campus.before-reset-lineage-3ee370b4-0af3-4dfb-b823-fd99a5ae0fb4.zip` before rewriting the legacy row. Before/after counts matched: 3 users, 3 applications, credential-epoch sum 2, 3 reset-token records and 2 audit events. All 8 Mailpit message IDs remained unchanged. A second startup passed normal migration validation without creating another backup. The app health endpoint reported UP and the inbox remained available. Private database/inbox files and build/test logs remain ignored by Git.

The final local run used Node 25.2.0, Java 21.0.12.1 compiling for Java 17, Maven 3.9.11 and actual PostgreSQL 17.11. Docker/container builds and hosted CI were not rerun for this combined revision; historical hosted results above apply only to their linked commits. Hosted HTTPS/proxy and external SMTP activation checks remain deployment work. No new actionable review finding remained after the fixes and follow-up review. The requested merge is local; no push or public deployment is included.

## 2026-10-29 application review fixes

See [review findings, fixes, and complete verification limits](docs/superpowers/reviews/2026-10-29-application-review-fixes.md).

- Clean Node 22.23.3 install, all 79 frontend tests, and production build passed; npm audit reported zero vulnerabilities.
- Clean Maven verification passed 140 backend tests and all 3 real PostgreSQL integration tests, with no skips. The report guard and all 16 CI guard unit tests passed.
- Backend validation used Java 21.0.12.1 and local disposable PostgreSQL 18.6, not the CI Java 17/PostgreSQL 17 Docker matrix. Docker was unavailable, and the cached Java 17 installation was incomplete.
- The final UI was rebundled; all 5 dist files matched the executable JAR. The bundled HTTP/multipart/Vite/restart smoke test passed.
- One independent reviewer checked the fixes twice; the final pass reported no actionable findings. Cross-tab UI behavior was covered in JSDOM, not a native browser.


## 2026-10-29 Spring Security endpoint matcher migration

- Replaced the three deprecated `AntPathRequestMatcher` instances in `AuthEndpoints` with `PathPatternRequestMatcher`. Login and registration remain POST-only; the CSRF path matcher retains all-method matching so the abuse filter's existing GET/HEAD and unsafe-request accounting remains unchanged.
- Three characterization tests passed against the original implementation and the replacement: exact case-sensitive paths, HTTP methods, trailing/extra segments, context paths, query independence, and servlet-path/path-info splits.
- Clean `mvn -Pwith-ui clean verify` passed all 143 backend tests with no failures or skips on local Java 21. Existing authentication, CSRF, context-path throttling, work-limit and session tests passed. No `AntPathRequestMatcher` compiler warnings remained; unrelated deprecation/runtime notices remain outside this change.
- The bundled HTTP/SMTP/native multipart/Vite proxy/restart smoke test passed. Frontend sources did not change. Java 17, PostgreSQL 17 and container builds are verified separately by this branch's hosted CI.
- The same independent read-only reviewer found no actionable findings or concrete bypass on firewall-accepted paths. Native encoded-path browser probes were not performed locally.

## Password Reset publication integration — October 29, 2026

Before publication, GitHub main had advanced to `402c347a32f448a76dacc3613b5210450df27733`. Integrated its account-isolation, complete-history, import-recovery and endpoint-matcher changes without replacing either history. Backend and independent frontend reviews found no remaining actionable integration defect after resolving shared transport and authentication-gate conflicts.

- Serialized logout retains `X-Campus-Account` until confirmed success; a failed logout retains the account boundary. Profile rechecks can bind a new identity while invalidating requests from the previous account. New regressions cover both, including recovery UI during an actual account change.
- Retained Vite 8.3.5, jsdom 29.1.1 and source-map-js 1.2.2. A fresh audit found a high-severity transitive development dependency issue in undici 7.30.0; its compatible 7.30.1 patch cleared the audit without changing the application API.
- Final local checks passed: 213 backend tests without failures/errors/skips, 105 frontend tests without failures/skips, the TypeScript/Vite production build, and npm audit with zero reported vulnerabilities. Source/lockfile conflict markers were removed and diff whitespace checks passed.
- These local integration checks used Java 21 and Node 25. Java 17, real PostgreSQL 17, container builds and the combined packaged HTTP/SMTP smoke are checked by the GitHub main-push CI run; older hosted runs above do not establish results for this new integration.
