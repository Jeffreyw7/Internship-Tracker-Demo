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

## What works

- Account registration, sign-in and sign-out using Spring Security sessions.
- Per-account applications with company, role, location, status, application date, deadline, follow-up date and notes.
- Add, edit, soft-delete, and undo deletion; interview stage scheduling, completion, reordering through submitted stage order, and removal.
- Follow-up completion and undo, immediate search and filters, status summaries, sorting, paging, CSV export, and explicit refresh.
- Database-backed optional sample applications at registration; these are illustrative, not verified vacancies.
- CSRF-protected writes, HttpOnly session cookies, BCrypt password hashing, request validation, ownership checks on all application operations, and optimistic concurrency checks.

Follow-ups are displayed in the workspace. They do not send notifications or create calendar events. The app does not include email verification, password reset, admin account management or recruitment integrations.

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

Sessions are in memory for this single-instance version. A multi-instance deployment needs shared sessions (for example Spring Session/Redis) or sticky routing, plus PostgreSQL, HTTPS, rate limiting and database backups. Password reset and email verification should be added before opening public self-service registration.

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
