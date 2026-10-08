# Validation record

## Final scalability review fixes - October 8, 2026

One fresh-context whole-branch reviewer found four Important issues and no Critical or Minor issues. All four were addressed in one regression-tested fix pass: stale follow-up/interview completion now checks the displayed application version before writing; midnight filter changes refresh due summaries; default native local startup now processes CSV jobs; regular PostgreSQL Compose supplies a restricted export worker/private shared volume and no longer advertises its unsupported distributed shortcut. Production/distributed APIs keep embedded export processing disabled and continue requiring shared private storage.

| Final check after fixes | Result |
| --- | --- |
| `npm test`; `npm run build` | 62 tests passed; strict TypeScript/Vite build passed |
| Maven `-Pwith-ui,postgres-it,distributed-it -Dpostgres.test.bin=... verify` | 112 backend and 12 real-service integration scenarios passed, zero skips; bundled JAR built |
| Java 17 Docker image build | All 112 backend tests passed |
| Default local startup/export integration | Actual scheduled job reached READY and its private CSV downloaded without a worker-enable override |
| `node tests/local_compose_smoke.mjs` | Default PostgreSQL Compose processed CSV with its restricted export role; API mount read-only, worker mount read-write |
| Real Chrome + `tests/http_smoke.mjs` | Full workspace/CSV/HTTP/restart checks passed using the ordinary local export-processing default |
| `node tests/distributed_smoke.mjs` | Final two-peer/private-storage/worker/cache/restart/Redis stopped+full/bad-migration qualification passed |
| `git diff --check` | Passed |

Regression RED evidence: two rescheduled activity tests submitted stale actions instead of returning 409; the controlled midnight test retained yesterday's summary; default local startup had no ExportWorker bean; regular Compose topology had no export processor. GREEN checks exercise the corrected behavior. A local Compose attempt hit an upstream image-download connection reset; pinning to the already-qualified PostgreSQL 17.11 and Mailpit 1.31.3 images allowed the fresh run to finish.

Final evidence: `test-results-review-final-node.log`, `test-results-review-final-build.log`, `backend/target/review-final-all.log`, `test-results-review-local-compose-green.log`, `test-results-review-final-browser.log`, and `test-results-review-final-distributed.log`. These are ignored local artifacts. The second load report, including image identities and service resource limits, is in ignored `backend/target/campus-test-d6c7ca72-e7b4-4609-b98f-9553672cfda4/measurements.json` and `measurements.md`; it preceded the final review fixes and is a modest local reference, not final hosted capacity acceptance. No further load run was needed for these local-startup/frontend correctness changes; hosted load and resilience remain pending.

All architecture decisions, reviewer dispositions and qualification limits are recorded in [the implementation record](docs/SCALABILITY-IMPLEMENTATION.md). Changes remain uncommitted on `scalability-design`; no new push, merge or deployment occurred. The overnight automation remains PAUSED.

## Scalability recovery qualification - October 8, 2026

Repository implementation now includes shared Redis sessions/atomic admission, bounded v2 reads and frontend caches, dedicated mail/export workers, private durable CSV jobs, and the two-API portable ingress/migration topology. This supersedes the partial October 4 checkpoint below. Hosted large-SaaS capacity and managed failover remain unqualified; the overnight automation stays paused.

| Fresh check | Result |
| --- | --- |
| `npm test`; `npm run build` | 59 passed; strict TypeScript and Vite build passed |
| Maven `-Pwith-ui,postgres-it,distributed-it -Dpostgres.test.bin=... verify` | 111 backend tests and 12 real-service integration scenarios passed, no skips; bundled JAR built |
| Java 17 Docker build | All 111 backend tests passed during the private deployment image build |
| Real Chrome + `tests/http_smoke.mjs` | Bounded workspace, full 116-application CSV, editing/conflict/undo, CSRF/login/logout, assets and H2 restart passed |
| `node tests/distributed_smoke.mjs` | Two-peer routing/session/CSRF/CRUD/logout, private ports/storage/workers, cache policy, API recovery, Redis stopped/full, and failed-migration gates passed |

Tests use disposable synthetic data. PostgreSQL scenarios validate fresh/legacy privileges, keyset/summary/activity behavior, quota races and fenced worker takeover. Actual child JVMs are terminated after private export upload and after real loopback SMTP acceptance; replacement workers recover without stale completion. Duplicate mail preserves its verification token and stable Message-ID, leaves the token unconsumed, and clears encrypted payload after recorded acceptance. Single-use confirmation remains covered by the verification suite.

Two regressions were reproduced before fixes: an uncommitted servlet response already using an output stream could not switch to a writer after `resetBuffer`; full reset now clears stale content/cookie headers, preserves correlation and returns safe 503. Export creation now sets retry eligibility with the same application clock used by the worker, eliminating date-dependent delayed claims. The disposable storage fixture now allocates capacity for both metadata and export collections.

Evidence logs are ignored local artifacts: `backend/target/scalability-final-all.log`, `test-results-scalability-final-node.log`, `test-results-scalability-final-build.log`, `test-results-scalability-final-browser.log`, and `test-results-distributed-smoke.log`. Immutable tested image identities are recorded in the disposable project's `release-manifest.json`. GitHub Actions is configured; its hosted workflow has not been executed in this session. The load experiment and final whole-branch review are recorded separately when finished. A matched original-version performance baseline, 300,000-account dataset, 1,500/s sustained / 2,000/s burst, multi-hour soak, cloud rollback/failover and isolated PITR remain pending approved hosting resources.

## Redis integration qualification - October 5, 2026

The Redis test failure reported as "Could not find a valid Docker environment" was caused by Docker Desktop's engine not running. Starting the installed Docker Desktop app made the Linux engine reachable; no application changes or Docker data reset were required.

Fresh Maven `-Pdistributed-it -Dit.test=DistributedSessionIT,RedisAuthRateLimiterIT test-compile failsafe:integration-test failsafe:verify` passed all 5 integration scenarios with zero failures, errors or skips. Tests used isolated `redis:7.4-alpine` containers on Docker Desktop 4.93.0 / Engine 29.8.1, Java 20 compiling release 17 and cached Maven 3.9.11. Coverage includes shared sessions/CSRF across two API instances, API restart/logout, concurrent shared admission limits, limiter restart, bounded cardinality/refill, Redis memory exhaustion and controlled errors after Redis shutdown. Shutdown-related connection warnings are expected in the outage tests. Evidence: ignored `backend/target/docker-redis-qualification.log`.

This verifies the local Redis integration scenarios previously blocked by the missing runtime. It does not establish managed Redis failover, production capacity, or distributed export/storage readiness. The overnight automation remains paused.

## Scalability implementation checkpoint - October 4, 2026

This checkpoint on `scalability-design` implements probes/metrics/resource budgets, opt-in Redis sessions and shared authentication admission, leased mail workers with separate database roles, bounded v2 application/activity reads and account summaries, and bounded frontend pages with on-demand details. Export job APIs, schema and CSV encoding are scaffolding: jobs remain PENDING because export processing and S3 storage are unfinished.

Checks rerun before the user-requested commit and push:

| Check | Result |
| --- | --- |
| `npm test` | 47 passed; no failures or skips |
| `npm run build` | TypeScript and Vite build passed |
| Maven `test` | 100 passed; no failures, errors or skips |
| Maven `-Pwith-ui -DskipTests package` | Bundled JAR built after the fresh frontend build; tests ran separately above |
| Maven `-Ppostgres-it -Dit.test=PostgresPrivilegesIT,WorkspaceReadIT,MailWorkerIT -Dpostgres.test.bin=... failsafe:integration-test failsafe:verify` | 4 real PostgreSQL scenarios passed; no failures, errors or skips |
| `CAMPUS_BROWSER_TEST=true node tests/http_smoke.mjs` | Real Chrome workspace flows and HTTP/restart persistence passed |

Checks used Node, Java 20 compiling release 17, cached Maven 3.9.11, disposable PostgreSQL 17.6/H2 databases and synthetic accounts. The first browser smoke invocation was blocked by sandbox loopback permissions; rerunning with reviewed network access passed. Logs are ignored local outputs named `test-results-commit-*.log` and `backend/target/commit-checkpoint-*.log`.

Real Redis qualification remains pending because Docker/Redis is unavailable. Container/S3 qualification, export processing/frontend export jobs, scalable ingress, CI/load/recovery tooling, Java 17 runtime qualification, hosted capacity/failover and the final independent whole-branch review remain unfinished. This commit is a partial implementation checkpoint, not completion of the scalability plan. The overnight automation is paused at the user's request.

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

## Local load experiment - October 8, 2026

The first complete disposable run seeded eight accounts, 5,353 applications and 16,059 stages. Reference 20/s started 400/400 offered arrivals; ramp 50/s started 999/1,000; combined mail/CSV 50/s started 1,499/1,500; one-minute overlap 50/s started 2,998/3,000. Foreground phases returned no 5xx/transport errors or 429, and their maximum route p99 was 219.6 ms initially, 16.3 ms on the ramp, 45.0 ms with mail/CSV, and 14.5 ms during overlap. Four exports reached READY and two mails reached SENT.

The 200/s burst started 2,489/3,000 arrivals and missed 511 through scheduler lag, with zero client capacity drops. It **does not qualify 200/s server throughput or identify server saturation**. Invalid-password 5/s admission started 50/50 with 29 legitimate 429 responses; its mixed-outcome p99 was 862.9 ms. Overlapping three APIs recorded eight runtime, one export-worker and three mail-worker database connections, against a configured 24-connection maximum. Redis used about 1.60 MB of its 64 MB budget. Measurements are a changed-stack reference, not an original-version comparison. Evidence: ignored `backend/target/campus-test-5cfc83b5-95d4-4a0d-bd46-5ffc50e1e848/measurements.json` and `measurements.md`.

Run Docker fixtures sequentially: concurrent unused-subnet discovery can collide, and concurrent stacks would distort these development-host measurements. A second run qualifies embedding the immutable image identities and explicit service CPU/memory limits directly in the measurement report. Hosted capacity, true server saturation, successful-auth peaks and multi-hour resilience still require approved load infrastructure.
