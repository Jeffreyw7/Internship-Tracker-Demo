# Code purpose guide

The source files contain comments above the main classes, functions, and logical sections. This guide covers the schema and configuration formats that are best kept separate from inline annotations.

## Request flow

1. `src/main.tsx` mounts `AuthGate`; its imported authentication-link stores capture and remove verification/reset fragments before React renders. `AuthGate` checks the session before opening the workspace.
2. `src/App.tsx` organizes the workspace views and delegates data changes to `useApplications`.
3. `src/data/applicationApi.ts` converts editor drafts into API requests. `src/data/api.ts` handles cookies, CSRF headers, and errors.
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

`backend/src/main/resources/db/migration/V2__user_theme_preference.sql` adds the account appearance preference. `V3__email_verification.sql` adds the verification boundary and durable delivery state:

- `campus_users.email_verified_at` records confirmation. `email_verification_exempt` is set only for accounts that existed when V3 ran; new rows default to false.
- `email_verification_tokens` stores one-way SHA-256 token hashes, expiry, consumption and revocation timestamps. Raw URL tokens are never stored.
- `email_outbox` stores the address and token in an AES-GCM encrypted payload, along with leasing, retry and terminal-state fields. Issuing a token and enqueueing its message share one database transaction.
- `auth_rate_limits` stores HMAC-protected subjects and database counters for request, address, confirmation and global mail budgets.

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

## Password reset architecture

Flyway `V4__password_reset.sql` adds credential epochs and sequence cutoffs, encrypted intake, digest-only challenges, private database control and pseudonymous security events. It generalizes the encrypted mail outbox to explicit verification/reset/notification purposes. `auth/reset` owns admission, short transactional completion, leased asynchronous issuance, health and retention. Session epoch enforcement is in `auth/CredentialVersionFilter.java`; frontend recovery/auth coordination remains in typed adapters and AuthGate. See [the recovery runbook](PASSWORD-RESET.md) and system design for transaction boundaries and release gates.
