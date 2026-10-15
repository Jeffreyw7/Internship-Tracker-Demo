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
