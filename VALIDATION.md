# Validation record

Dark-mode update validated September 23, 2026, using Java 21.0.12.1, Maven 3.9.11, Node 25.2.0, and Spring Boot 3.5.16. The original September 15 validation covered the baseline application.

| Check | Result |
| --- | --- |
| TypeScript + Vite production build | Passed |
| Client/API, theme-state, and palette tests | 22 passed |
| Spring Boot integration and migration tests against H2 | 16 passed; no failures or skipped tests |
| Flyway V1-to-V2 account upgrade + Hibernate validation | Passed; existing accounts default to System |
| Executable JAR with bundled React assets | Built successfully; bundled files match the frontend build |
| Real HTTP smoke test | Passed, including theme API validation and CSRF |
| File-backed H2 persistence after stopping and restarting the JVM | Passed; Dark account preference survived |

Java tests cover registration, password hashing, incorrect credentials, real CSRF tokens, authenticated CRUD, per-user isolation, optional sample data, request validation, stage updates/removal, pagination/search, missing version headers, stale writes, soft delete and undo. Timestamp precision is normalized to database microseconds so creation timestamps remain stable across round-trips.

The HTTP smoke test launches the executable JAR on loopback using a temporary database and synthetic account. It checks the website and static assets, registers and signs in, saves Dark appearance, rejects invalid and CSRF-free preference writes, creates/updates/deletes/restores a record, rejects a stale update, restarts the JVM, signs in again, verifies both the record and appearance, then checks logout. It does not alter user data.

The test-only Mockito configuration uses the subclass mock maker, avoiding JVM agent attachment in restricted environments. The integration tests use real application services, security, controllers, migrations and database operations.

Not verified in this session: browser/visual QA (no browser was connected), the PostgreSQL/Docker configuration, high-volume load behavior, or a hosted Java deployment. Semantic palette pairs and selected-state indicators passed automated contrast checks. A read-only review found an account-switch 401 race and faint indicators; both were fixed and covered by tests. Codex Security's preflight passed, but its scan worker hit a usage limit before a formal report could be finalized. Source review found no other confirmed security finding. The earlier Sites deployment remains the separate local-data prototype.

Run `npm test`, `npm run build`, and `backend/mvnw -Pwith-ui verify` (on Windows use `mvnw.cmd`). After building the bundled JAR, `python tests/http_smoke.py` repeats the HTTP/restart check using only Python's standard library.
