# Repository Agent Guide

## Project Overview

- Purpose: per-account internship application tracking, interview stages, follow-ups, and appearance preferences.
- Frontend: React 19, TypeScript 5.9 (strict), Vite 8; plain CSS and Lucide icons.
- Backend: Java 17, Spring Boot 3.5, Spring Security sessions, Spring Data JPA, Flyway.
- Database: file-backed H2 locally; PostgreSQL with the production profile.
- Frontend flow: components -> `useApplications` -> typed API adapters -> shared HTTP transport.
- Backend flow: controllers -> transactional, owner-scoped services -> repositories.
- References: `docs/API.md` for contracts; `docs/CODE_GUIDE.md` for architecture; `VALIDATION.md` for recorded checks and limits.

## Build & Development Commands

Prerequisites: Node.js >=22.18, JDK >=17. Maven is supplied by the wrapper;
its first run downloads Maven and dependencies. Python 3 is optional for HTTP smoke tests.
Run each command from the listed directory. The table uses Windows/PowerShell examples,
except the explicitly labeled macOS/Linux row. For Maven commands on macOS/Linux,
replace `.\mvnw.cmd` with `./mvnw`. `start.ps1` is Windows-specific;
use the documented Maven alternative below on macOS/Linux.

| Task | Working directory | Command |
| --- | --- | --- |
| Install frontend dependencies | Repository root | `npm ci` |
| Resolve backend dependencies | `backend/` | `.\mvnw.cmd dependency:resolve` |
| Backend dev server | `backend/` | `.\mvnw.cmd spring-boot:run` |
| Frontend dev server (separate terminal) | Repository root | `npm run dev` |
| Type check and frontend build | Repository root | `npm run build` |
| Frontend tests | Repository root | `npm test` |
| Backend tests | `backend/` | `.\mvnw.cmd test` |
| Package bundled app (after frontend build) | `backend/` | `.\mvnw.cmd -Pwith-ui clean package` |
| Start bundled UI (after frontend build; Windows) | Repository root | `powershell -ExecutionPolicy Bypass -File .\start.ps1` |
| Start bundled UI (after frontend build; macOS/Linux) | `backend/` | `./mvnw -Pwith-ui spring-boot:run` |
| HTTP/restart smoke test (after bundled packaging) | Repository root | `python tests/http_smoke.py` |

- Linting/formatting: no lint script or formatter is configured. `npm run build` includes TypeScript checking, not linting.
- Backend defaults to `http://127.0.0.1:8080`; use the printed Vite URL for frontend development.
- Smoke tests launch the bundled JAR with a temporary H2 database and synthetic account.
- For behavior changes, run relevant checks: `npm test` and `npm run build` for frontend changes; `.\mvnw.cmd test` from `backend/` for backend changes. Use the bundled HTTP smoke test for changes affecting HTTP integration or restart persistence.
- For documentation-only changes, verify referenced commands and paths; application suites are unnecessary. Report checks not run and why.

## Code Standards & Conventions

- Naming: PascalCase React component files; camelCase hooks/utilities; Java classes in existing feature packages under `com.campus.tracker`.
- Preserve strict TypeScript and typed DTO boundaries; avoid weakening types to bypass errors.
- Keep HTTP transport/adapters in `src/data`, derived logic in `src/domain`, and application mutations in `src/hooks/useApplications.ts`.
- Preserve serialized application mutations, stale-response protection, and server-confirmed application state.
- Use constructor injection, transactional services, validated request DTOs, and authenticated ownership checks. Never trust an owner ID from request JSON.
- Whitelist editable payload fields; exclude server-owned metadata. Unknown JSON fields are rejected by the backend.
- Convert empty date inputs to API `null`; preserve local-calendar date handling rather than introducing UTC date shifts.
- Use semantic CSS tokens in `src/theme/tokens.css` and the existing theme provider/controller.
- Follow nearby formatting and concise purpose comments; avoid unrelated reformatting.

## Gotchas / Guardrails

- Verify versions, commands, paths, and behavior against the repository. If a documentation brief or this guide conflicts with the implementation, report the discrepancy and document verified behavior; do not change application code merely to match the brief or guide.
- Run Java from `backend/`: the H2 URL is relative to the process working directory, normally storing data in `backend/data/`.
- Only one backend can open the local H2 database. Stop the existing instance to release a lock; never delete database files. Changing the HTTP port does not fix a database lock.
- Flyway owns schema evolution. Add versioned migrations under `backend/src/main/resources/db/migration`; do not edit applied migrations. Keep Hibernate `ddl-auto: validate`.
- The `with-ui` profile copies existing `dist/` into the JAR; it does not install or build frontend dependencies. Rebuild the frontend before bundling UI changes.
- Vite proxies `/api` to `127.0.0.1:8080`; changing the backend port also requires updating the proxy. A static frontend deployment alone does not deploy Java.
- Application records have no localStorage fallback. Appearance caching is intentional; signed-in theme preferences persist in the database.
- Preserve same-origin session cookies and CSRF handling in `src/data/api.ts`, including token refresh after login. Never automatically replay failed writes.
- Application update/delete/restore require `If-Match`; stale versions return HTTP 409. Preserve soft deletion, undo, and ownership checks for every operation.
- Keep `public/theme-init.js` bootstrap keys and behavior synchronized with runtime theme logic. Retain account-switch protection so stale responses cannot affect a newer session.
- Never commit credentials, `.env`, database files, `node_modules/`, `dist/`, or `backend/target/`. Production requires explicit database credentials and defaults to secure cookies with registration disabled.
- Frontend tests import TypeScript directly through Node; retain the minimum Node version and explicit `.ts` imports in modules used by these tests.
- Backend tests use isolated in-memory H2 and the test-only Mockito subclass mock maker. Passing H2 tests does not establish PostgreSQL compatibility.
- The UI loads paginated application history into memory for filtering and summaries; preserve that behavior unless deliberately redesigning data loading.
