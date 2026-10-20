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

Limits: PostgreSQL checks used installed PostgreSQL 18.6; Flyway emitted its existing warning that this version is newer than its tested support range. The Docker/PostgreSQL 17 path, production load tests, and browser UI QA were not run for this patch. The request reviewer noted that a deliberately paused commit transaction is not a separate new HTTP test; permit lifetime through completion/rollback was checked by source review, while existing lifecycle/PostgreSQL tests cover transaction rollback. No source-backed bypass or regression remained in either review.

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
