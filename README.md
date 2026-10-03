# Campus Internship Tracker — Java + React

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

Open **http://localhost:8080**. Choose **Create an account**, enter your own name, email and password, and leave sample applications checked to explore immediately. No real email is sent or required for this local demo. There are no preconfigured accounts or shared passwords. Each account receives its own optional sample dataset.

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

- Account registration, sign-in and sign-out using Spring Security sessions.
- Per-account applications with company, role, location, status, application date, deadline, follow-up date and notes.
- Add, edit, soft-delete, and undo deletion; interview stage scheduling, completion, reordering through submitted stage order, and removal.
- Follow-up completion and undo, immediate search and filters, status summaries, sorting, paging, CSV export, and explicit refresh.
- Database-backed optional sample applications at registration; these are illustrative, not verified vacancies.
- CSRF-protected writes, HttpOnly session cookies, BCrypt password hashing, request validation, ownership checks on all application operations, and optimistic concurrency checks.

Automated reminders support interviews, saved application deadlines and follow-ups, with configurable timing, snooze and daily digests. Password recovery is also available after mail configuration. Signup email verification, admin account management and recruitment integrations remain separate features.

## Planned updates

The following features are planned for later updates:

- Signup email verification
- Calendar integration
- Admin account management
- Dark mode
- A friendlier user interface

## Job posting and contact archive

In an application's editor, save the posting URL, paste its description, add the primary recruiter and referral source, and record compensation with currency and pay period. Saved descriptions come from the database, so they remain available after the original posting disappears. Changing a description captures another version; clearing it keeps earlier versions in **Description history**. Soft deletion and undo retain the archive.

**Export CSV** includes current posting/contact/compensation fields as a summary. **Download description archive** in application details exports the application metadata and all saved descriptions as JSON. Description reads load only for the opened application. There is no automatic posting scraper or conversion between hourly and annual amounts.

See [the posting archive design](docs/superpowers/specs/2026-10-03-job-posting-contact-details-design.md) and [API contract](docs/API.md#job-posting-and-description-history). After bundling, `node tests/posting_http_smoke.mjs` verifies real HTTP behavior and backend restart persistence using a temporary H2 database; `--serve` leaves the synthetic instance available for browser checks.

## Automated reminder configuration

Open **Reminders** in the workspace and enable reminders, then **Save settings**. Choose category toggles, lead times (0–30 days), a timezone and local reminder time. Interviews currently store dates only; reminder time is a notification time. Defaults are one day before interviews, three days before saved application deadlines, and on the follow-up date. Reminder preferences default to disabled.

**As each reminder becomes due** releases reminders individually. **Daily digest** groups newly due reminders at the selected local digest time, once per day. Items added after that day's digest join the next day's digest, except interviews and deadlines occurring today: after the digest cutoff, these notify individually when due so they can reach you before expiry. This uses the account timezone and works with or without email. The reminder center refreshes every 30 seconds while open. Snooze for 15 minutes, one hour, one day or one week; the same digest rules and same-day exception apply to snoozed items. Dismiss removes that occurrence. Application edits, interview completion, follow-up completion, deletion and closed statuses cancel affected reminders. Samples never generate reminders or email.

Reminders work in the app without SMTP, and persist in the database across restarts. For optional email to the account's saved address, configure the Java host:

| Variable | Setting |
| --- | --- |
| `REMINDER_MAIL_ENABLED` | `true`; defaults to `false` |
| `REMINDER_MAIL_FROM` | Authorized SMTP sender address |
| `SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD` | Same SMTP connection as password mail; default port 587 |
| `SMTP_AUTH`, `SMTP_STARTTLS` | Default `true`; disable only for a local SMTP sink |
| `REMINDER_WORKER_ENABLED` | Default `true` |
| `REMINDER_WORKER_DELAY` | Milliseconds between ticks; default `30000` |

Then select **Also send to my account email** in Reminders. Reminder SMTP can be configured independently of password recovery. Email failures retry up to six attempts, and failed delivery remains visible beside the in-app reminder. Jobs use database leases, and ordinary successful sends are deduplicated; a crash after SMTP accepts a message can still cause a duplicate on retry. Already sending mail cannot be recalled. SMTP credentials/provider setup, hosted deployment and production delivery are not provisioned by this feature.

See [the reminder system design](docs/superpowers/specs/2026-10-02-automated-reminders-design.md) for the component diagram, lifecycle and deployment limits.

## Database options

H2 is the default for a self-contained local project. To use local PostgreSQL with Docker:

1. Copy `.env.example` to `.env` and replace the placeholder database password.
2. Run `docker compose up --build`.
3. Open http://localhost:8080 and create an account.

The Compose stack binds the app only to **127.0.0.1** and does not expose the database port. Its named PostgreSQL volume survives container restarts. Do not run `docker compose down -v` if you want to retain data.

For an existing PostgreSQL instance, select `SPRING_PROFILES_ACTIVE=prod` and set:

| Variable | Value |
| --- | --- |
| `DATABASE_URL` | A JDBC URL, such as `jdbc:postgresql://localhost:5432/campus` |
| `DATABASE_USERNAME` | Database user |
| `DATABASE_PASSWORD` | Database password |
| `REGISTRATION_ENABLED` | `true` for account setup; production defaults to `false` |
| `COOKIE_SECURE` | `true` with HTTPS; `false` only for local HTTP |
| `SERVER_ADDRESS` | Defaults to `127.0.0.1`; containers use `0.0.0.0` |
| `PORT` | Defaults to `8080` |

Flyway creates the schema; Hibernate validates it. No passwords are included in the source. The production profile requires database credentials instead of silently falling back to H2. Keep environment files out of Git.

## Architecture

Source comments explain each main code section. See [docs/CODE_GUIDE.md](docs/CODE_GUIDE.md) for the end-to-end request flow, database schema, and build configuration purposes.

- `backend/.../auth`: accounts, password hashing, login identity and CSRF endpoint.
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

The UI currently fetches the user's paginated history into memory to keep its existing cross-view filtering and summaries immediate. For very large histories, connect the table directly to server-side queries and add separate aggregate/activity endpoints. The API boundary already supports that change; this project has not been load-tested.

Sessions are in memory for this single-instance version. A multi-instance deployment needs shared sessions (for example Spring Session/Redis) or sticky routing, plus PostgreSQL, HTTPS and database backups. Password recovery and email-confirmed changes are available after configuring SMTP below. Signup email verification remains a separate feature.

## Password recovery and account security

Use **Forgot password?** on login for a 30-minute recovery link. **Account security** in the workspace verifies your current password and sends a 15-minute change link; open it in the initiating browser. Confirmation ends the account's sessions and returns to normal sign-in. Application data is retained.

Password mail defaults to disabled. Configure these environment variables on the Java host:

| Variable | Setting |
| --- | --- |
| `PASSWORD_MAIL_ENABLED` | `true` |
| `PASSWORD_PUBLIC_ORIGIN` | HTTPS application origin; HTTP is permitted on loopback |
| `PASSWORD_MAIL_FROM` | Authorized SMTP sender address |
| `PASSWORD_ENCRYPTION_KEY` | Base64-encoded, securely generated 32-byte AES key |
| `PASSWORD_ENCRYPTION_KEY_ID` | Key identifier, default `v1` |
| `PASSWORD_RATE_LIMIT_SECRET` | Independently generated secret of at least 32 UTF-8 bytes |
| `SMTP_HOST`, `SMTP_PORT` | Provider hostname and port; default port 587 |
| `SMTP_USERNAME`, `SMTP_PASSWORD` | Provider credentials |
| `SMTP_AUTH`, `SMTP_STARTTLS` | Both default `true`; STARTTLS is required when enabled |

Keep secrets outside Git and stable across restarts. The key ID must match queued encrypted messages; drain the queue before replacing the single configured encryption key. An old-key ring is not implemented. For a local SMTP sink only, set `SMTP_AUTH=false` and `SMTP_STARTTLS=false`.

Challenges are hashed and delivery jobs encrypted. SMTP failures retry until link expiry or delivery exhaustion; password changes enqueue a notification. A database credential version rejects old sessions on their next API request; already authorized requests are not cancelled. Local file-backed H2 disables delayed writes (`WRITE_DELAY=0`) so acknowledged challenge/outbox writes survive abrupt JVM restarts; retain that setting if overriding the H2 URL.

Properties `campus.password.reset-ttl`, `change-ttl`, and `limits.*` control lifetimes/limits. Defaults: requests 5/minute and 30/hour/IP; recipient mail 1/minute, 5/hour, 10/day; global challenge mail 1000/day; confirmations 10/minute/IP and 10/15 minutes/token. Unknown-account and recipient-suppressed requests count toward attempt limits but do not consume the global delivery quota. Forwarded headers are not enabled; reverse-proxy deployments must configure trustworthy client addressing. No deployment or SMTP account is provisioned automatically.

After bundling, run `node tests/password_http_smoke.mjs` for real HTTP/SMTP/restart verification with synthetic accounts and a temporary database. `python tests/http_smoke.py` also checks CRUD and password flows. Both resolve Java's real executable to avoid Windows Oracle launcher shims leaving the JVM running during restart checks.

See [docs/API.md](docs/API.md) for request examples and the endpoint contract.

## Tests

```sh
# At the project root
npm test
npm run build

# In backend/ (use .\mvnw.cmd on Windows)
./mvnw test
```

Client tests verify payload mapping, CSRF/version headers, pagination, conflicts, delete/undo requests and view selectors. Java integration tests exercise real Spring Security, controllers, services, Flyway and H2, including cross-account isolation. See `VALIDATION.md` for the recorded checks and limits.

## Deployment status

This ZIP is the Java edition for local development or a JVM/container host. **The earlier private Sites URL still runs the original local-data prototype.** It has not been connected to this Java API. A Spring Boot JAR needs a Java-capable host; copying the React build alone to a static host does not deploy the backend. No public deployment or access change was made for this edition.

## References

- [Spring Boot 3.5 system requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
- [Spring Boot 3.5.16 release](https://spring.io/blog/2026/06/25/spring-boot-3-5-16-available-now/)
