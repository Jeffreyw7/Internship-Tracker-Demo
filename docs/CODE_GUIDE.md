# Code purpose guide

## Password lifecycle

`PasswordController` owns validated routes, limits, initiating-session binding and local session teardown. `PasswordService` locks account then challenge, validates purpose/email/version/binding, and changes credentials transactionally. Recovery queries only the account ID before locking to avoid stale managed account state during concurrent confirmation. Change issuance serializes transaction completion and session-binding replacement. `CredentialVersionFilter` compares the database credential version with the login principal before authorization.

`PasswordChallengeRepository` persists token digests and lifecycle state. `AuthRateLimiter` uses HMAC identifiers and independently committed database buckets; an insertion conflict rolls back separately before bounded increments. Flyway V2 adds the schema without changing V1. `PasswordRequestSizeFilter` bounds password JSON before deserialization, including chunked requests.

The `mail` package separates encrypted enqueueing, AES-GCM payload protection, SMTP templates, and leased delivery. SMTP runs outside database transactions. The worker rechecks eligibility, cancels expired/revoked jobs, erases terminal secrets, and cleans metadata after seven days. Duplicate mail after a worker crash still uses single-use tokens.

`AuthGate` owns recovery/security/completion navigation. Focused forms call typed `passwordApi` adapters through shared transport. `passwordLink.ts` clears fragments before session lookup; tokens/passwords stay in memory. Confirmation clears cached CSRF even on an uncertain result and is never replayed automatically.

The source files contain comments above the main classes, functions, and logical sections. This guide covers the schema and configuration formats that are best kept separate from inline annotations.

## Request flow

1. `src/main.tsx` mounts `AuthGate`, which checks the current session before opening the workspace.
2. `src/App.tsx` organizes the workspace views and delegates data changes to `useApplications`.
3. `src/data/applicationApi.ts` converts editor drafts into API requests. `src/data/api.ts` handles cookies, CSRF headers, and errors.
4. Spring Security checks authentication and CSRF before a backend controller handles the request.
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

## Build and configuration files

- `package.json`: defines frontend packages, development/build/test commands, and the supported Node version. JSON does not support comments.
- `package-lock.json`: generated dependency resolution data for repeatable Node installs; it is not hand-annotated.
- `tsconfig.json`: configures TypeScript checking and module resolution for the React frontend.
- `backend/pom.xml`: defines Java dependencies, the Spring Boot build, and the optional `with-ui` profile that copies built frontend assets into the JAR.
- `backend/mvnw`, `backend/mvnw.cmd`, and `backend/.mvn/wrapper/maven-wrapper.properties`: vendor-provided Maven launcher code and its distribution configuration. Wrapper internals are left intact.
- `backend/src/main/resources/application.yml`: local defaults, including file-backed H2 and session settings.
- `backend/src/main/resources/application-prod.yml`: production database credentials and safer cookie and registration defaults.
- `backend/src/test/resources/application-test.yml`: isolated in-memory database settings for tests.
- `backend/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`: selects Mockito's subclass mock maker to avoid runtime agent attachment in restricted environments.
- `vite.config.ts`: configures React tooling and the local API proxy.
- `index.html`: provides the browser document and root element that React mounts into.
- `Dockerfile`: builds the frontend and backend, then runs the JAR as an unprivileged user.
- `compose.yaml`: starts the local application and PostgreSQL with persistent database storage.
- `start.ps1`: checks for an existing local instance and launches the bundled application from the backend directory.
- `.gitignore` and `.dockerignore`: exclude local data, credentials, dependencies, and generated outputs from Git or the Docker build context.
- `.gitattributes`: normalizes text line endings while preserving platform-specific script requirements.
- `.env.example`: documents the local Compose database-password variable using a placeholder.
- `.vscode/settings.json`: configures the Java editor's null analysis setting.

Compiled output in `dist/` and `backend/target/`, installed dependencies, local databases, and third-party tooling are not source documentation targets.
