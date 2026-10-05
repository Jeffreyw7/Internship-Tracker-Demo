# Scalability Design Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for native execution, or superpowers:subagent-driven-development if the user selects delegated execution. Steps use checkbox syntax for tracking.

**Goal:** Implement the repository changes needed for a replicated internship-tracker API, bounded frontend reads, independent delivery/export workers, and reproducible deployment qualification.

**Architecture:** Retain the existing Spring transaction and ownership boundaries. Add an opt-in distributed profile with Redis sessions and atomic admission limits, additive v2 read APIs, durable export jobs, independent workers, and a same-origin static/API ingress. Preserve local H2 development and existing mutation contracts.

**Tech Stack:** Java 17, Spring Boot 3.5.16 and its managed dependency versions, PostgreSQL 17, Redis, React 19, strict TypeScript 5.9, Vite 8, Node >=22.18.0, Docker Compose, GitHub Actions, and a Node HTTP qualification runner.

**Spec:** [Scalability architecture and gap assessment](../specs/2026-10-04-scalability-design.md).

**Baseline:** `45728d1` on `scalability-design`. The user authorized committing and pushing the current implementation checkpoint on October 4, 2026. The plan remains incomplete; checkboxes, the execution ledger and `VALIDATION.md` record verified progress.

## Global constraints

- Preserve same-origin cookies, session fixation protection, post-login CSRF refresh, HttpOnly/Secure/SameSite policy, two-hour idle expiry, and logout invalidation.
- Preserve owner-scoped queries, typed DTOs, unknown-field rejection, application `@Version`, `If-Match`, HTTP 409/428, soft deletion, and undo.
- Retain serialized mutations in `src/hooks/useApplications.ts`, confirmed server state, stale-response protection, and no automatic replay of failed writes.
- Date-only fields stay local calendar dates. Request empty dates map to null; existing detail responses retain empty strings.
- New read APIs must preserve stage-name search, whole-account counts, Offer activity behavior, and complete account CSV export with BOM/formula protection.
- Flyway owns schema; do not edit applied V1-V3 migrations. Keep Hibernate validation and separate migration/runtime credentials.
- Never modify a user's existing database for tests. Use disposable databases/accounts; do not delete local databases or volumes.
- No secrets, `.env`, database files, generated outputs, or dependencies enter Git. Committing and pushing this checkpoint to `scalability-design` is authorized; merging, deployment and paid provisioning still require separate instruction.
- Qualification targets are 1,500 total HTTP requests/second sustained and 2,000 burst; 99.9% core availability, p95 <300 ms/p99 <1 second, auth p95 <2 seconds, normal-provider mail acceptance p95 <60 seconds. They remain assumptions to measure, not existing guarantees.
- Optional caching, replicas, external search, a broker, and sharding require a measured bottleneck before implementation.

## Execution scope and environment

The application and portable deployment deliverables below do not require cloud credentials. The hosting choice is being requested separately; portable containers are the planning default. Cloud-specific provisioning, a hosted CDN, managed-service failover, multi-zone load qualification, and restore exercises stay explicitly pending until a target environment, budget, credentials, and operational targets exist. Do not represent local Compose as managed HA.

Hosting recommendation for the current application: Render for staging/initial release, with its Docker web service, managed PostgreSQL, Redis-compatible Key Value, and background workers. This is a recommendation, not a selected or provisioned environment. The strict final HA target favors AWS ECS Fargate/RDS Multi-AZ/ElastiCache/S3: [Render PostgreSQL HA](https://render.com/docs/postgresql-high-availability) uses asynchronous replication and may lose recent writes, whereas [RDS Multi-AZ DB instances](https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.MultiAZSingleStandby.html) support synchronous standby replication. Initial Render hosting must not be reported as satisfying the final acknowledged-write failover target. Keep platform configuration outside application business logic.

Node and Java are available. Docker, Redis server, and Maven were not found on PATH during inspection. The repository's previous validation used cached Maven/PostgreSQL binaries because the Windows wrapper had an empty `Target` failure. At execution, resolve the wrapper/cache with read-only checks, then use the recorded fallback if necessary; do not silently skip integration profiles. Docker-dependent checks can run in CI, but their results must be obtained before marking those deliverables verified.

## Review focus

1. An old account's delayed page/detail/export response must never affect the next signed-in account; pin this in Tasks 2, 7, and 9.
2. A due date around midnight/DST, an Offer with a scheduled stage, and a null sort date must keep existing semantics; pin this in Tasks 5-7.
3. Saturated or unavailable Redis must not disable limits, revive logout sessions, or expose private data; pin this in Tasks 2-3 and 10.
4. A terminated worker after external acceptance must recover without overwriting a newer lease or treating a duplicate email as a second confirmation; pin this in Tasks 4 and 8.
5. A large export or rolling deployment must not starve login/mail or exceed the database connection ceiling; pin this in Tasks 8 and 11.

## Shared interfaces

Keep existing mutation and detail endpoints unchanged. Define new frontend DTOs in `src/data/workspaceApi.ts` and matching Java records in `application/read/WorkspaceDtos.java`:

- `ApplicationListItem`: id, company, role, location, status, appliedDate, deadline, followUpDate, createdAt, updatedAt, version, sample, and nullable `nextActivity` (id, kind, label, date, stageId). Exclude notes and the full stage collection.
- `CursorPage<T>`: `content`, nullable `nextCursor`, `hasNext`, `size`, `readAt`; no mandatory count query.
- `ApplicationFilters`: q, status, tab (`all|active|saved|closed`), dueOnly, today (`YYYY-MM-DD`), sort (`updated|company|deadline|applied`), size, cursor.
- `WorkspaceSummary`: total, active, interviews, offers, due, scheduledInterviews, hasSamples, per-status counts, readAt. Counts cover all visible records for the authenticated account, independent of list search/filter.
- `ActivityItem`: id, applicationId, company, role, location, applicationStatus, applicationVersion, kind (`Interview|Follow-up|Deadline`), label, date, nullable stageId. Full detail is fetched before completing an activity.
- `ExportView`: id, state (`PENDING|RUNNING|READY|FAILED|CANCELLED|EXPIRED`), createdAt, expiresAt, nullable failureCode, nullable downloadPath. Owner/object key/lease/recipient/credentials never appear in client DTOs.

v2 API: `GET /api/v2/applications`, `GET /api/v2/applications/summary?today=...`, `GET /api/v2/activities?kind=...&q=...&size=...&cursor=...`, `POST /api/v2/exports`, `GET /api/v2/exports/{id}`, `DELETE /api/v2/exports/{id}`, `GET /api/v2/exports/{id}/download`. Require CSRF on export creation/cancellation and authentication/ownership on every job route.

Default application page size is eight to retain the current UI, with maximum 100. Activity pages default to 25, maximum 100. Use Previous/Next navigation and a bounded cursor history instead of fabricating arbitrary numbered-page access. A 300 ms search debounce and a maximum of 20 cached pages/100 detail records per account bound browser state; account changes clear all caches immediately.

## Task 1: Instrument readiness, dependency failures, and resource budgets

**Files:** Modify `backend/pom.xml`, `backend/src/main/resources/application.yml`, `application-prod.yml`, `backend/src/main/java/com/campus/tracker/config/SecurityConfig.java`; create `backend/src/main/java/com/campus/tracker/config/OperationsConfiguration.java`, `common/RequestCorrelationFilter.java`, `common/DependencyFailureFilter.java`, `backend/src/test/java/com/campus/tracker/OperationsIntegrationTest.java`.

**Interfaces:** Produce `/livez` and `/readyz` on the application port, private `/actuator/prometheus`, safe JSON 503 on unavailable dependencies, request-correlation IDs, bounded timeout/pool settings. Retain `/api/health` as the launcher marker.

- [x] Write `OperationsIntegrationTest`: anonymous GET `/livez` =200 with no internals; public `/actuator/env`, `/actuator/metrics`, and `/actuator/prometheus` cannot disclose data; unavailable required DB makes `/readyz`=503 without making `/livez` unhealthy; committed responses are not overwritten by failure handling.
- [x] Run `./mvnw -Dtest=OperationsIntegrationTest test` from backend and observe missing endpoints/failure policy before implementation.
- [x] Add Actuator, Prometheus registry, explicit management authorization, no-store API responses, correlation IDs without payload logging, graceful draining, and dependency-aware readiness. Session/limiter indicators are conditional on Task 2/3 deployment mode; SMTP does not determine web readiness.
- [x] Expose configurable pool/connection/statement/drain budgets; use 30-second drain and strictly shorter downstream deadlines. Record the combined API/worker/rollout connection ceiling in deployment docs, without claiming arbitrary pool sizes guarantee throughput.
- [x] Re-run the focused test and `./mvnw test`; keep operational metrics private through network routing as well as application authorization.

## Task 2: Add Redis sessions and qualify cross-instance authentication

**Files:** Modify `backend/pom.xml`, `config/SecurityConfig.java`, `auth/CampusPrincipal.java`; create `backend/src/main/resources/application-distributed.yml`, `config/DistributedSessionConfiguration.java`, `backend/src/test/java/com/campus/tracker/DistributedSessionIT.java`; modify `compose.yaml` and `.env.example`.

**Interfaces:** Opt-in `distributed` profile uses Spring Session Redis, `JSESSIONID`, two-hour idle expiry, environment-specific namespace and versioned serializer. Ordinary local/test profiles retain container sessions. `CampusPrincipal.sessionIdentity()` returns the identity with no retained password hash; successful authentication stores it in the security context.

- [ ] Write a real-Redis integration scenario starting two application processes/contexts: CSRF on A, login on B, rotated CSRF on A, create/update on B, restart A, then logout on B and `/auth/me` on A=401. Assert no sticky routing is required, no password hash is retained in stored principal, and old CSRF is rejected after login.
- [ ] Run `./mvnw -Pdistributed-it -Dit.test=DistributedSessionIT verify`; establish the current process-local session failure using a test harness that can start both instances without enabling the missing profile.
- [ ] Add Boot-managed Redis/session dependencies and explicit profile activation; configure cookie deletion/name together. Configure/test the chosen serializer with Spring Security types and `CampusPrincipal`, including missing/expired/malformed attributes and mixed application-version round trips.
- [ ] Configure Redis private access/authentication, bounded timeouts, namespace, noeviction, and memory alerts. Verify Redis outage/session-write failure yields 503 without falling back to local sessions; test logout during failover on the selected deployment platform.
- [ ] Run the focused distributed scenario, ordinary backend suite, and existing HTTP smoke test; local H2 startup must not require Redis.

## Task 3: Share authentication budgets and validate proxy identity

**Files:** Create `auth/abuse/AuthAdmissionLimiter.java`, `RedisAuthRateLimiter.java`, `AuthAdmissionProperties.java`, `backend/src/main/resources/redis/auth-admission.lua`; modify existing abuse limiter/configuration/filter/security classes, `auth/verification/AuthRateLimiter.java`, configuration; create `auth/abuse/RedisAuthRateLimiterIT.java`, `TrustedProxyIntegrationTest.java`.

**Interfaces:** `AuthAdmissionLimiter.acquire(String key, AuthRateLimiter.Policy policy): AuthRateLimiter.Decision`; current in-memory limiter implements this interface for local mode. Distributed mode uses atomic Redis time/refill/admission, HMAC-protected keys, bounded namespace cardinality, TTL, and positive Retry-After. `campus.verification.global-mail-daily-limit` defaults to the existing 1,000 and can be raised through configuration.

- [ ] Write tests asserting two independent clients share exactly the configured budget, refill timing matches token-bucket semantics, restrictions survive API restart, new keys are rejected at the cardinality limit, and Redis loss returns 503 rather than allowing work or pretending to be a policy denial.
- [ ] Write proxy tests: untrusted X-Forwarded-For cannot change identity; a configured trusted ingress can distinguish clients; multi-hop spoofing is stripped; legitimate shared-NAT behavior is explicit. Add a configurable-mail-budget test proving default behavior remains unchanged.
- [ ] Run the new tests before changing production behavior; retain existing abuse tests as compatibility checks.
- [ ] Implement bounded Lua admission with a same-slot namespace/cardinality index, independent per-bucket expiry, and cleanup bounded per call. Keep per-process BCrypt permits covering all four existing password-costly routes. Retain database verification fixed windows and their committed race fixes.
- [ ] Make trusted proxy ranges explicit for container-native forwarded-header handling; retain NONE locally and block direct API ingress in Task 10. Run backend suite, Redis profile, and request-spoof tests.

## Task 4: Separate and harden email delivery workers

**Files:** Create `worker/WorkerApplication.java`, `config/WorkerConfiguration.java`, `application-worker.yml`, `mail/OutboxClaimRepository.java`, `mail/OutboxMaintenance.java`, `mail/OutboxKeyRing.java`, `deploy/postgres/upgrade-workers.sql`; modify `mail/EmailOutboxWorker.java`, `MailConfiguration.java`, `MailProperties.java`, `OutboxCrypto.java`, `Dockerfile`, `deploy/postgres/roles.sql`, `runtime-grants.sql`, `verify-runtime.sql`, provisioning and privilege tests; extend mail tests and add `MailWorkerIT.java`.

**Interfaces:** Worker launches with `WebApplicationType.NONE` and an explicit role `mail|export`. `OutboxClaimRepository.claim(int limit, UUID leaseOwner): List<ClaimedMail>` claims PostgreSQL rows in short SKIP LOCKED transactions; H2 uses tested conditional claims. `OutboxKeyRing` selects the active encrypt key and a permitted decrypt key by ID.

- [ ] Write tests for two workers claiming distinct jobs, lease takeover, fenced completion after expiry, provider timeout/retry, disabled API polling, no public worker listener, old/new key overlap, payload erasure, and repeated cleanup limited to a bounded batch.
- [ ] Run the focused mail tests/profile to expose current embedded scheduling, key-rotation, and claim/cleanup limitations.
- [ ] Isolate the worker context so HTTP controllers/security/session dependencies are not loaded. Retain the local embedded scheduler as a convenience; distributed API disables it. Use bounded executor/claim capacity, one leased job per available permit, and SMTP outside transactions. Do not queue leases behind busy executor threads.
- [ ] Move maintenance to a separate schedule with bounded batches. Add queue age/backlog/retry/permanent-failure gauges without recipient labels. Preserve stable Message-ID, single-use tokens, at-least-once delivery, and eligibility rechecks.
- [ ] Add restricted `campus_mail_worker` and `campus_export_worker` roles through the existing administrative provisioning scripts and explicit `upgrade-workers.sql`; grants are applied by the migrator's packaged grant script. Task 4 needs no schema migration. Old installations must run the administrative role upgrade before the new grant script; failure keeps new services stopped. Run mail suite, PostgreSQL privileges, and worker-termination scenarios; document keys retained until queued jobs drain/expire.

## Task 5: Implement bounded application listing and cursor semantics

**Files:** Create `application/read/WorkspaceDtos.java`, `ApplicationReadController.java`, `ApplicationReadService.java`, `ApplicationReadRepository.java`, `ApplicationCursorCodec.java`, `backend/src/main/resources/db/migration/V4__application_read_indexes.sql`, `backend/src/test/java/com/campus/tracker/ApplicationReadIntegrationTest.java`; extend `PostgresPrivilegesIT.java`.

**Interfaces:** `ApplicationReadService.list(UUID ownerId, ApplicationFilters filters): CursorPage<ApplicationListItem>`. Controller uses authenticated principal only; cursor includes version/filter fingerprint/last tuple and never grants ownership. Search uses parameterized escaped literal substring matching plus an owner-scoped stage EXISTS.

- [x] Write tests for exactly eight returned items and hasNext from nine matches; no notes/full stages in response; stage-name search; all tabs/status/due filters; cross-owner/deleted exclusion; invalid/oversized/mismatched cursors; equal sort keys; company case/collation; null date placement; and local today around midnight/DST.
- [x] Run `./mvnw -Dtest=ApplicationReadIntegrationTest test`; missing v2 endpoints must fail HTTP assertions before implementation.
- [x] Implement size+1 projections and keyset queries, never collection fetch-join or mandatory list COUNT. Updated/applied sorts descend; company/deadline ascend; ID desc is the stable tie-breaker, null dates last. Define company ordering as normalized lowercase database ordering and document the deliberate change from browser locale ordering.
- [x] Fetch next activity in a bounded set for returned IDs, preserving Offer activities and Saved deadlines. Add only measured indexes matching actual predicates/order; PostgreSQL EXPLAIN confirms the existing V1 index supports the updated-order keyset query with a backward scan; V4 adds only measured maintenance indexes. Other read candidates remain gated by query plans.
- [x] Run H2 and real PostgreSQL parity/EXPLAIN checks and existing mutation tests. Keep `/api/applications` compatibility intact throughout.

## Task 6: Add consistent summaries and bounded activity feeds

**Files:** Extend `application/read/WorkspaceDtos.java`, `ApplicationReadController.java`, `ApplicationReadRepository.java`; create `WorkspaceSummaryService.java`, `ActivityReadService.java`, `backend/src/test/java/com/campus/tracker/WorkspaceSummaryIntegrationTest.java`, `ActivityReadIntegrationTest.java`.

**Interfaces:** `summary(UUID ownerId, LocalDate today): WorkspaceSummary`; `activities(UUID ownerId, String kind, String q, LocalDate today, int size, String cursor): CursorPage<ActivityItem>`. Responses include `readAt`; independent list/summary responses are explicitly not one snapshot.

- [x] Write tests comparing expected full-account counts with an eight-item page, every per-status count, sample flags, scheduled-stage counts, overdue follow-ups, Offer inclusion, closed exclusions, and Saved deadlines. Activity cursors order by date, kind, and stable ID, with deterministic ties.
- [x] Run both new test classes against missing endpoints before implementation.
- [x] Compute summary counts in one SQL statement or a short REPEATABLE_READ transaction; do not deserialize every application. Return bounded activity projections with the same escaped/stage-inclusive search semantics as Task 5.
- [x] Run focused and full backend tests, plus PostgreSQL query-plan checks with skewed account histories. Leave result caching disabled initially.

## Task 7: Move the frontend to bounded reads and on-demand details

**Files:** Create `src/data/workspaceApi.ts`, `src/domain/workspaceController.ts`, `tests/workspace.test.mjs`, `tests/workspace-api.test.mjs`; modify `src/hooks/useApplications.ts`, `src/App.tsx`, `src/components/Shared.tsx`, `src/components/ApplicationDrawer.tsx`, `src/data/applicationApi.ts`, and styles only as needed for loading/Previous/Next states.

**Interfaces:** `workspaceApi.list(filters, isCurrent)`, `.summary(today, isCurrent)`, `.activities(filters, isCurrent)`, `.detail(id, isCurrent)`. `createWorkspaceController(dependencies)` exposes subscribe/getSnapshot, setFilters, next/previous/refresh/loadDetail/clearAccount. `useApplications` retains existing save/remove/restore entry points and invalidates read caches after confirmation.

- [x] Write direct-Node tests proving first render fetches only one page and summary, 300 ms debouncing, bounded caches, stable previous/next navigation, filter-reset behavior, and stale page/detail/error suppression after account change.
- [x] Add mutation tests proving serialization remains synchronous, stale-version edits refresh reads, writes are never automatically replayed, and completion loads full current detail before changing a stage/follow-up. Failed detail loads must not save a fabricated draft.
- [x] Run `node --test tests/workspace*.test.mjs` before replacing the existing all-history path.
- [x] Add typed adapters/controller, detail loading, global summaries, bounded activity paging, and separate loading/error states. Generalize CompanyButton's app input to identity/display fields; avoid inventing empty notes/stages to satisfy an Application type.
- [x] Update the workspace to Previous/Next, account-wide badges/cards, server next-activity previews, and on-demand editor detail. Keep CSV temporarily functional through the existing full-history adapter until Task 9 replaces export; normal workspace loading must never call that adapter.
- [x] Run `npm test`, `npm run build`, browser flow checks, and backend HTTP smoke tests. Verify keyboard search, drawer editing, completion/undo, deleted restore, and filter/date parity.

## Task 8: Implement durable export jobs, storage, and isolated processing

**Files:** Create `export/ExportDtos.java`, `ExportController.java`, `ExportService.java`, `ExportRepository.java`, `ExportWorker.java`, `CsvWriter.java`, `ExportObjectStore.java`, `FileExportObjectStore.java`, `S3ExportObjectStore.java`, `ExportProperties.java`, new `V5__export_jobs.sql`, `application-export-worker.yml`, tests `ExportIntegrationTest.java`, `ExportWorkerTest.java`, `CsvWriterTest.java`, `ExportStorageIT.java`; modify grants/roles, worker configuration, pom and deployment configuration.

**Interfaces:** ExportObjectStore provides put/open/delete by generated internal key; filesystem mode is for isolated local testing, S3-compatible private storage for distributed deployment. Export creation commits before 202. Export workers use their own role/pool and fenced leases; job APIs always derive owner from session.

- [ ] Write tests for CSRF/ownership on create/status/cancel/download, guessed UUID=404, exactly two active jobs/account, lease takeover, cancellation races, expiry, output publication failure, fenced stale completion, and cleanup of abandoned objects. Quotas: maximum two active jobs/account, 100 MiB/object, 24-hour retention; expose validated overrides.
- [ ] Write real CSV assertions for BOM, CRLF, quotes/newlines, formula prefixes, all fields/stages, and full history beyond the first page. Live export traversal orders by immutable createdAt/ID with job-start creation cutoff; no point-in-time values/deletion guarantee.
- [ ] Run focused tests before adding durable jobs. Use isolated S3-compatible storage for distributed checks; do not test uploads against a user's bucket.
- [ ] Add V5 job schema, explicit role grants, atomic per-account creation quota using account row locking, bounded reads, lease renewal/fencing, capped streaming object writes, expiry/cancellation, and authorized download proxy. Restrict worker read access to required application/stage columns and worker write access to jobs only.
- [ ] Ensure mail/export executors, pools, deadlines, and roles are separate. Run H2 tests, PostgreSQL privileges and two-worker races, isolated storage checks, and termination during publication. V4 belongs to Task 5 indexes and V5 to export schema; role provisioning in Task 4 is a separate administrative procedure, matching the repository's existing privilege separation.

## Task 9: Wire full-history exports into the workspace

**Files:** Create `src/data/exportApi.ts`, `src/domain/exportController.ts`, `tests/export.test.mjs`; modify `src/data/api.ts`, `src/App.tsx`, and styles for accessible export status.

**Interfaces:** `exportApi.create/status/cancel/download` with account guards. Binary download transport shares same-origin credentials and status/session handling; it must not pass a CSV response through the existing JSON-only request function. Poll every two seconds only while an active job is visible, with cancellation on account change and no write retries.

- [ ] Write tests: exporting an eight-item page produces the full job, CSV download uses authenticated transport, expiry/failure states are actionable, old-account status/download cannot affect a new account, polling stops on terminal state/logout, and cancellation is CSRF-protected.
- [ ] Run the Node export tests against missing controller/adapters before implementation.
- [ ] Replace browser all-history CSV generation with job creation/status/download; remove the temporary export use of full-history listing from Task 7. Retain CSV filename/local date semantics and release download object URLs.
- [ ] Run frontend suite/build and browser-to-worker-to-download end-to-end checks with more than 100 applications, quoted notes, formula prefixes, and multiple stages.

## Task 10: Provide portable ingress, replicated containers, and migration gating

**Files:** Create `compose.scalable.yaml`, `deploy/nginx/nginx.conf`, `deploy/redis/redis.conf`, `deploy/OPERATIONS.md`, `tests/distributed_smoke.mjs`; modify `Dockerfile`, `.dockerignore`, `.env.example`, `docs/DATABASE-SECURITY.md`.

**Interfaces:** A disposable stack exposes only same-origin ingress on loopback; two API instances, static UI, Redis sessions/limits, PostgreSQL, mail/export workers, local SMTP capture, and private S3-compatible export storage are internal. Worker launch targets and the one-shot migrator are explicit; app startup depends on successful migrations/grants.

- [ ] Write distributed smoke checks for round-robin session/CSRF lifecycle, private API/database/Redis/storage ports, uncached API responses, immutable hashed assets, revalidated HTML/bootstrap, and inaccessible management endpoints. A disposable bad migration must prevent API and worker startup.
- [ ] Run checks against the current topology to establish missing distributed functionality; no existing volume or user's database may be reused.
- [ ] Add static/API routing that strips untrusted forwarding headers, preserves cookies/CSRF/If-Match/query/errors, limits body sizes, and does not replay failed writes. Use a pinned tested Redis/ingress/storage image version and record immutable release digests during CI packaging.
- [ ] Configure Redis noeviction/auth/private access, separate worker credentials/pools, generated disposable secrets, resource limits, startup/readiness/drain checks, and migration failure gating. Do not claim Compose provides multi-zone PostgreSQL/Redis HA.
- [ ] Build/run the stack and execute distributed smoke, Redis unavailable/full cases, API termination, and worker restart. Record any environment-blocked checks instead of passing them silently.

## Task 11: Add CI, representative data, open-loop load reporting, and recovery procedures

**Files:** Create `.github/workflows/ci.yml`, `tests/load/seed.mjs`, `tests/load/run.mjs`, `tests/load/report.mjs`, `tests/load/README.md`, `deploy/RECOVERY.md`; update `package.json`, `README.md`, `docs/API.md`, `docs/CODE_GUIDE.md`, `VALIDATION.md`, `docs/superpowers/specs/2026-10-04-scalability-design.md`.

**Interfaces:** Load runner accepts explicit base URL, account fixture file, rate, duration, concurrency ceiling, and output path. It records scheduled/started/completed/dropped requests, route-template latency/error/rejection counts, and achieved arrivals. Seeder creates synthetic accounts/history in an explicitly designated disposable target, with safety checks and no raw credentials in reports.

- [ ] Write runner tests showing overload reports missed arrivals instead of lowering offered load, bounded client memory, latency percentiles from real samples, and credential redaction. Test skewed seeded data and CSV/mail contention independently of CRUD timing.
- [ ] Run runner tests before implementation, then implement rate scheduling, mixed foreground/polling/auth HTTP flows, reports, and explicit saturation/soak scenarios. Large 60-million/180-million datasets are qualification runs, not an everyday CI requirement.
- [ ] Wire CI for Node tests/build, Maven tests, real PostgreSQL privileges/workflows, Redis/distributed tests, image/Compose migration gating, worker/storage checks, and bundled HTTP smoke. No public deployment step or registry credential is assumed.
- [ ] Record baseline and changed-stack measurements, then run ramp/burst/soak to identify saturation. Exercise rollout overlap and combined connection budgets; report actual rates, p95/p99, legitimate 429/503, pool wait, heap/GC, Redis memory, queue age, and export/mail interference.
- [ ] Document safe backup/role/key inventory, isolated restore, record/privilege comparisons, session invalidation and duplicate-mail handling, expand/backfill/switch/contract rollback, and the cloud-managed failover requirements. Never test restore onto an existing user database.
- [ ] Run all repository suites and available integration checks, update contracts/commands and validation evidence, and review the full diff for ownership/session/concurrency regressions. Record every test not run and why.

## Task 12: Qualify the selected hosted environment and optimize from evidence

**Files:** Extend `deploy/OPERATIONS.md`, `deploy/RECOVERY.md`, `VALIDATION.md`; add cloud-specific IaC only after hosting requirements are supplied, and new migrations only for measured query/index changes.

**Interfaces:** Private managed PostgreSQL with tested synchronous instance/zone failover, managed Redis with tested session/limit failure behavior, private object storage, HTTPS same-origin edge/CDN, managed container deployment across zones, secrets, metrics, and verified backup storage.

- [ ] Resolve hosting provider/account, budget, public origin, region(s), managed-service configurations, secret sources, approved SLO/RPO/RTO and retention. Prepare reviewable IaC before any billable provisioning/public deployment approval.
- [ ] Test first-zone/instance loss, routine acknowledged-write preservation, Redis failover/logout, provider degradation, deployment rollback, and isolated point-in-time restore; measure disaster RPO <=5 minutes and RTO <=60 minutes.
- [ ] Run 1,500 HTTP requests/second sustained, 2,000 burst, auth workload, skewed-history and multi-hour soak scenarios on approved infrastructure. Report whether SLOs and resource budgets are met, with actual cost/resource configuration.
- [ ] Implement optional summary caching, query replica, external search, broker, partitioning, or sharding only if a report identifies the bottleneck, the consistency/operational contract is approved, and an independent test demonstrates the improvement. Otherwise leave these alternatives unimplemented.

## Plan self-review and handoff

- Spec coverage: Tasks 1-3 establish observability/shared state; Tasks 5-9 bound reads and exports; Task 4 separates mail; Tasks 10-11 provide deployable/qualifiable portable infrastructure; Task 12 explicitly covers cloud HA/recovery and conditional optimizations.
- Compatibility: v1 mutations/detail remain; v2 lists use lightweight types; activity completion fetches full current detail; CSV remains whole-account; local H2/SMTP capture remain available.
- Review-focus mapping: each of the five stated failures has explicit task assertions. Shared DTO and route names are declared once above and reused.
- External dependencies: no cloud provider/budget/account or Docker is assumed. Hosted qualification and unavailable integration checks remain visible acceptance conditions, not silently reduced scope.
- Migration sequence: V4 is the Task 5 index migration; V5 is the Task 8 export schema. Task 4 provisions new worker roles through explicit admin scripts, not H2-incompatible Flyway role creation. Worker/export grants must not broaden existing runtime credentials or rewrite applied security policy.
- Execution choice: native implementation is recommended because the frontend, read contracts, sessions, and worker roles share interfaces and need consistent integration. No subagents have been started. Review this written plan before selecting execution.
