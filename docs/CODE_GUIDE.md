# Code purpose guide

The source files contain comments above the main classes, functions, and logical sections. This guide covers the schema and configuration formats that are best kept separate from inline annotations.

## Request flow

1. `src/main.tsx` captures and removes a verification token from the URL fragment before rendering, then mounts `AuthGate`, which checks the current session before opening the workspace.
2. `src/App.tsx` organizes the workspace views and delegates data changes to `useApplications`.
3. `src/data/workspaceApi.ts` requests bounded v2 application/summary/activity projections and on-demand full details. `src/domain/workspaceController.ts` owns a 20-page/100-detail cache, cursor history, search debounce, and account generation guards. `useApplications` submits serialized writes through `applicationApi.ts` and invalidates reads after server confirmation. The full-history compatibility helper is temporarily used only for explicit CSV export. `src/data/api.ts` handles cookies, CSRF headers, and errors.
4. Spring Security checks authentication and CSRF before a backend controller handles the request. A pending account receives `EMAIL_VERIFICATION_REQUIRED` only after its password is accepted.
5. Controllers validate inputs and pass the authenticated user's ID to services. Services enforce ownership and business rules before repositories access the database.
6. Response DTOs return the saved data and version to the frontend. The version prevents an older editor from overwriting a newer change.

## Database schema

`backend/src/main/resources/db/migration/V1__initial_schema.sql` creates the initial schema. Its contents are unchanged because Flyway records migration checksums in databases that have already applied it.

- `campus_users` stores each account's UUID, unique email, display name, BCrypt password hash, and creation time. Plaintext passwords are not stored.
- `applications` stores opportunity details and dates. Its `owner_id` foreign key associates the record with an account. Status constraints restrict the permitted pipeline values.
- `sample` identifies illustrative records. `deleted` hides a record while retaining it for undo. `version` supports optimistic concurrency checks. Creation and update timestamps record when data changed.
- Application indexes begin with owner and deletion state, then support common update-time, status, follow-up, and deadline queries.
- `interview_stages` stores named, ordered interview steps, dates, and constrained stage states. Its foreign key links each stage to an application; physical deletion of that application cascades to its stages.
- The stage index supports loading a parent's interviews in display order.

`backend/src/main/resources/db/migration/V2__user_preferences.sql` adds the account appearance preference. `V3__email_verification.sql` adds the verification boundary and durable delivery state:

- `campus_users.email_verified_at` records confirmation. `email_verification_exempt` is set only for accounts that existed when V3 ran; new rows default to false.
- `email_verification_tokens` stores one-way SHA-256 token hashes, expiry, consumption and revocation timestamps. Raw URL tokens are never stored.
- `email_outbox` stores the address and token in an AES-GCM encrypted payload, along with leasing, retry and terminal-state fields. Issuing a token and enqueueing its message share one database transaction.
- `auth_rate_limits` stores HMAC-protected subjects and database counters for request, address, confirmation and global mail budgets.

`V4__application_read_indexes.sql` adds token-expiry/outbox-reference indexes for bounded mail maintenance. Real PostgreSQL EXPLAIN confirmed the existing V1 owner/update/ID index supports the new default descending keyset order by scanning backward; an additional duplicate read index was removed before shipping.

The additive v2 read path uses JDBC projections and size+1 queries, with no mandatory page count or full application/stage graph loading. Next-activity previews are selected for at most 100 returned IDs. Summary counts use one SQL snapshot; separate list and summary calls remain independent. Company sorting uses lowercase database order, and client calendar dates drive due counts. Existing detail/mutation routes retain their ownership, version, deletion and CSRF behavior.

`V5__export_jobs.sql` introduces durable job metadata and publication-intent tracking for private objects. Export creation/status/cancellation/expiry/quota APIs and the CSV encoder are implemented; processing and shared private storage are still in progress. Until the complete worker/export UI is verified, the workspace retains its existing explicit full-history CSV helper.

The opt-in `distributed` profile uses allowlisted versioned Redis session values and atomic Redis authentication admission. Local profiles retain container sessions and process-local admission. Successful login strips the password hash before storing the security context. Five real Redis integration scenarios passed locally on October 5, 2026; see `VALIDATION.md`. Managed failover and production capacity remain unqualified.

The mail worker has a separate non-web entry point, restricted database role, bounded executor with no queued leases, short PostgreSQL SKIP LOCKED claims, and fenced outcomes. SMTP runs outside database transactions. Separate bounded maintenance, active/retired decryption keys, and cached recipient-free metrics support operation. Distributed API instances disable embedded polling; local development can retain it.

## Build and configuration files

- `package.json`: defines frontend packages, development/build/test commands, and the supported Node version. JSON does not support comments.
- `package-lock.json`: generated dependency resolution data for repeatable Node installs; it is not hand-annotated.
- `tsconfig.json`: configures TypeScript checking and module resolution for the React frontend.
- `backend/pom.xml`: defines Java dependencies, the Spring Boot build, and the optional `with-ui` profile that copies built frontend assets into the JAR.
- `backend/mvnw`, `backend/mvnw.cmd`, and `backend/.mvn/wrapper/maven-wrapper.properties`: vendor-provided Maven launcher code and its distribution configuration. Wrapper internals are left intact.
- `backend/src/main/resources/application.yml`: local defaults, including file-backed H2, session settings and loopback SMTP capture on port 1025.
- `backend/src/main/resources/application-prod.yml`: production database, SMTP, trusted public origin and secret requirements, with safer cookie and registration defaults.
- `backend/src/test/resources/application-test.yml`: isolated in-memory database settings for tests.
- `backend/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`: selects Mockito's subclass mock maker to avoid runtime agent attachment in restricted environments.
- `vite.config.ts`: configures React tooling and the local API proxy.
- `index.html`: provides the browser document and root element that React mounts into.
- `Dockerfile`: builds the frontend and backend, then runs the JAR as an unprivileged user.
- `compose.yaml`: starts the local application, PostgreSQL with persistent database storage, and a loopback-only Mailpit capture inbox.
- `start.ps1`: checks for an existing local instance and launches the bundled application from the backend directory.
- `.gitignore` and `.dockerignore`: exclude local data, credentials, dependencies, and generated outputs from Git or the Docker build context.
- `.gitattributes`: normalizes text line endings while preserving platform-specific script requirements.
- `.env.example`: documents the local Compose database-password variable using a placeholder.
- `.vscode/settings.json`: configures the Java editor's null analysis setting.

Compiled output in `dist/` and `backend/target/`, installed dependencies, local databases, and third-party tooling are not source documentation targets.

## Replicated deployment and asynchronous exports

The workspace now uses bounded v2 pages, separate account summaries/activity feeds and on-demand full details. It keeps at most 20 page histories and 100 detail records per account, clears them at account changes, and retains serialized server-confirmed mutations and If-Match conflicts. CSV generation runs through durable owner-scoped jobs and a private worker/storage path, with guarded binary transport and visible status/cancel/download controls.

`compose.scalable.yaml` provides the separate static gateway, two API replicas, private PostgreSQL/Redis/S3-compatible services and non-web mail/export workers. Dedicated credentials, short fenced leases, pool limits, startup migration gates and API cache/proxy controls are documented in `deploy/OPERATIONS.md`. Public probes have a stateless security chain and do not resolve a caller's Redis session cookie; readiness still checks DB/Redis and authenticated requests fail closed during dependency loss.

The load harness lives in `tests/load/`: generated disposable data, open-loop arrivals, dropped-load accounting, bounded latency reservoirs, resource samples and no credentials in reports. CI runs Java 17/Node tests, real-service integration, packaged HTTP and the private Compose migration-failure test. Hosted capacity/HA/restore remains an explicit acceptance stage, described in `deploy/RECOVERY.md`; passing a local fixture does not establish those guarantees.
