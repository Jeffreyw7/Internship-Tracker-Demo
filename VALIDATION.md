# Validation record

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
