# Campus Internship Tracker â€” Java + React

The complete Java edition of the Campus workspace: a **Java 17 / Spring Boot 3.5.16 REST backend**, a **React + TypeScript frontend**, and a relational database. Application data is stored in the Java database; there is no localStorage fallback.

## Quick start in VS Code

Install a JDK **17 or newer** and **Node.js 22.18 or newer**. Open this project folder in VS Code. Maven is supplied by the included wrapper; the first run downloads Maven and Java dependencies.

From the project root:

```sh
npm ci
npm run build
cd backend
```

On **Windows PowerShell**:

```powershell
powershell -ExecutionPolicy Bypass -File ..\start.ps1
```

The Windows launcher detects a healthy Campus Tracker instance on the configured address and port and prints its URL instead of starting a duplicate. You can also run `powershell -ExecutionPolicy Bypass -File .\start.ps1` directly from the project root after building the frontend.

On **macOS/Linux**:

```sh
./mvnw -Pwith-ui spring-boot:run
```

Open **http://localhost:8080**. Choose **Create an account**, enter your own name, email and password, and leave sample applications checked to explore immediately. New accounts must confirm their email before signing in. For local development, verification messages are sent to an SMTP capture server; start one on `127.0.0.1:1025`, or use the Docker Compose setup below. There are no preconfigured accounts or shared passwords. Each account receives its own optional sample dataset.

The default H2 database is saved in `backend/data/` when you run from `backend/`. Stop with Ctrl+C. Your accounts and applications remain after restarting. Browser reloads do not reset data. Sessions end on a backend restart; sign in again.

For live frontend development, run the backend with `./mvnw spring-boot:run` (Windows: `.\mvnw.cmd spring-boot:run`), then run `npm run dev` in a second terminal at the project root. Vite proxies `/api` to Spring Boot on port 8080. Open the Vite URL printed in the terminal.

### Startup says "Process terminated with exit code: 1"

This is Maven's summary; the underlying Spring Boot error appears earlier in the terminal output. If it says `The file is locked` or `Database may be already in use` for `backend/data/campus.mv.db`, another process has the H2 database open. Only one backend instance can use this embedded database at a time.

1. Open http://localhost:8080 (or your configured port). If the app is running, use that instance.
2. To restart, press **Ctrl+C** in the terminal running the backend, or stop it in VS Code's Run/Debug controls if it was launched there. Then run the launcher again.
3. Keep `backend/data/campus.mv.db`: it contains your saved accounts and applications. Changing the HTTP port does not resolve a database lock.

The health endpoint at http://localhost:8080/api/health returns `{"status":"UP","application":"campus-tracker"}` when the backend is ready (JSON key order may vary).

## Build one runnable Java application

From the root, first run `npm ci` and `npm run build`. Then:

```sh
cd backend
./mvnw -Pwith-ui clean package
java -jar target/campus-tracker-2.0.0.jar
```

Use `.\mvnw.cmd` in place of `./mvnw` on Windows. The `with-ui` profile copies the built React files into the executable JAR, so Java serves both the website and `/api` from the same origin. The ordinary backend build does not download or build Node dependencies.

New accounts require a password with at least 12 characters, including an uppercase letter, a lowercase letter, a number, and a symbol. Unicode letters, numbers, and symbols are supported. Passwords must fit within 72 UTF-8 bytes for BCrypt; spaces do not count as symbols. The server enforces the same policy as the signup form. Existing accounts can still sign in with their original passwords.

## What works

- Account registration, email verification, sign-in and sign-out using Spring Security sessions. Accounts created before the verification migration remain eligible to sign in.
- Per-account applications with company, role, location, status, application date, deadline, follow-up date and notes.
- Add, edit, soft-delete, and undo deletion; interview stage scheduling, completion, reordering through submitted stage order, and removal.
- Follow-up completion and undo, immediate search and filters, status summaries, sorting, paging, CSV export, and explicit refresh.
- Database-backed optional sample applications at registration; these are illustrative, not verified vacancies.
- CSRF-protected writes, HttpOnly session cookies, BCrypt password hashing, request validation, ownership checks on all application operations, and optimistic concurrency checks.
- Light, Dark, and System appearance, with the signed-in choice saved to the account and refreshed on other devices when they regain focus or while visible.

Follow-ups are displayed in the workspace. They do not send notifications or create calendar events. The app does not include password reset, admin account management or recruitment integrations.

## Planned updates

The following features are planned for later updates:

- Follow-up email notifications
- Calendar integration
- Password reset
- Admin account management
- A friendlier user interface

## Database options

H2 is the default for a self-contained local project. To use local PostgreSQL with Docker:

1. Copy `.env.example` to `.env` and replace all five database password placeholders with distinct secrets.
2. Run `docker compose up --build`.
3. Open http://localhost:8080 and create an account.
4. Open the local capture inbox at http://localhost:8025, follow the verification link, and enter the signup password to activate the account.

The Compose stack binds the app and Mailpit inbox only to **127.0.0.1** and does not expose the database or export-worker port. A restricted export worker writes CSV files to a private named volume mounted read-only by the API. Its PostgreSQL and export volumes survive container restarts. The Compose verification keys are synthetic local values; use independent secrets in every hosted environment. Do not run `docker compose down -v` if you want to retain data. For replicated Redis sessions/limits and shared object storage, use the complete `compose.scalable.yaml` topology described in [operations](deploy/OPERATIONS.md).

For an existing PostgreSQL instance, select `SPRING_PROFILES_ACTIVE=prod` and set:

| Variable | Value |
| --- | --- |
| `DATABASE_URL` | A JDBC URL, such as `jdbc:postgresql://localhost:5432/campus` |
| `DATABASE_USERNAME` | Restricted runtime user (campus_runtime) |
| `DATABASE_PASSWORD` | Database password |
| `REGISTRATION_ENABLED` | `true` for account setup; production defaults to `false` |
| `COOKIE_SECURE` | `true` with HTTPS; `false` only for local HTTP |
| `SERVER_ADDRESS` | Defaults to `127.0.0.1`; containers use `0.0.0.0` |
| `PORT` | Defaults to `8080` |
| `PUBLIC_ORIGIN` | Public HTTPS origin used to build verification links, such as `https://tracker.example.edu` |
| `MAIL_SENDER` | Verified sender address used for verification email |
| `SMTP_HOST` / `SMTP_PORT` | SMTP relay endpoint; port defaults to `587` |
| `SMTP_USERNAME` / `SMTP_PASSWORD` | SMTP relay credentials |
| `SMTP_AUTH` / `SMTP_STARTTLS` | Default to `true` in production; disable only for a trusted local capture server |
| `OUTBOX_KEY_ID` | Identifier for the active email-outbox encryption key |
| `OUTBOX_ENCRYPTION_KEY` | Base64-encoded 32-byte AES key; keep this stable while queued mail exists |
| `VERIFICATION_RATE_LIMIT_SECRET` | Secret of at least 24 characters used to protect rate-limit identifiers |

The separate migration job runs Flyway; the production web process only validates the schema with Hibernate. The production profile requires database, SMTP, HTTPS-origin, outbox-key, and rate-limit settings instead of silently falling back to development values. Keep environment files out of Git. Registration defaults to disabled in production; enable it only after SMTP delivery and monitoring are ready.

## Architecture

Source comments explain each main code section. See [docs/CODE_GUIDE.md](docs/CODE_GUIDE.md) for the end-to-end request flow, database schema, and build configuration purposes.

- `backend/.../auth`: accounts, password hashing, login identity, email-verification tokens and rate limiting, and the CSRF endpoint.
- `backend/.../mail`: encrypted transactional outbox, retry worker, and SMTP transport.
- `backend/.../application`: DTOs, JPA entities, repository, owner-scoped service, REST controller and sample-data service.
- `backend/.../config`: Spring Security setup.
- `backend/.../common`: consistent API errors and a minimal health endpoint.
- `backend/src/main/resources/db/migration`: versioned database schema.
- `src/data/api.ts`: session-aware HTTP transport and CSRF handling.
- `src/data/applicationApi.ts`: typed application API adapter.
- `src/hooks/useApplications.ts`: async state and mutation coordination.
- `src/components`: account gate, application editor and shared UI.
- `src/domain`: frontend data types, date handling and view selectors.

The API uses server-side pagination, optional search/status filters, bounded page sizes and stable sorting. Indexes cover owner/status, owner/updated time, follow-up dates and deadlines. Stage collections are batched rather than joined into paginated parent queries. Every write compares `If-Match` with a JPA version field and returns HTTP 409 for a stale edit. Soft-deleted records are retained for undo; no retention/purge policy is implemented.

The UI requests bounded cursor pages, whole-account summaries and bounded activities from v2 endpoints, and loads full application details on demand. Its cache holds at most 20 pages and 100 details. Full CSV jobs use bounded processing: native H2 startup embeds the worker, while PostgreSQL Compose and distributed deployments use a separate worker. Downloads use authenticated, owner-scoped jobs. Export contents remain a live traversal with a creation cutoff, rather than a point-in-time snapshot.

Local H2 mode uses in-memory sessions. The distributed profile uses Redis for shared sessions and atomic authentication limits, PostgreSQL with separate API/migration/worker roles, and private object storage. `compose.scalable.yaml` provides a loopback qualification stack with two API instances, same-origin static ingress and separate mail/export workers. Production still requires HTTPS, managed failover, tested backups and capacity qualification on the selected hosting environment. Password reset remains a separate public-service lifecycle gap.

See [docs/API.md](docs/API.md) for request examples and the endpoint contract.

## Tests

```sh
# At the project root
npm test
npm run build

# In backend/ (use .\mvnw.cmd on Windows)
./mvnw test
```

Client tests verify verification-link handling, auth payloads, CSRF/version headers, pagination, conflicts, delete/undo requests and view selectors. Java integration tests exercise real Spring Security, controllers, services, Flyway and H2, including verification, outbox delivery, migration behavior and cross-account isolation. See `VALIDATION.md` for the recorded checks and limits.

After creating the bundled JAR, run `npm run test:smoke` from the project root to exercise the packaged UI, SMTP verification, authentication, application writes and restart persistence through real HTTP.

## Deployment status

This ZIP is the Java edition for local development or a JVM/container host. **The earlier private Sites URL still runs the original local-data prototype.** It has not been connected to this Java API. A Spring Boot JAR needs a Java-capable host; copying the React build alone to a static host does not deploy the backend. No public deployment or access change was made for this edition.

## References

- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring Boot 3.5.16 release](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)

## Authentication and database hardening

Authentication now has bounded peer-IP/account limits, a shared limit on active password/account-creation work, and a budget for anonymous CSRF session allocation. Throttled requests return HTTP 429 with Retry-After. See [authentication controls](docs/AUTHENTICATION-SECURITY.md) for configuration and operational limits.

The PostgreSQL Compose stack now separates provisioning, migrations, and runtime access. Set all three distinct password values in .env.example. The web container receives only campus_runtime credentials; a one-shot migration service must finish successfully before it starts. For external PostgreSQL deployments, run MigrationMain separately with campus_migrator credentials before starting the production web application; production no longer runs Flyway inside the web process.

**Existing PostgreSQL volumes require the explicit upgrade procedure.** Updating environment variables does not alter existing roles or passwords. Preserve the volume, test a backup restore, and follow [database deployment and upgrade instructions](docs/DATABASE-SECURITY.md).
