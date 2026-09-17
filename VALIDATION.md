# Validation record

Validated September 15, 2026, using OpenJDK 17.0.20, Maven 3.9.11, Node 24.19.0 and Spring Boot 3.5.16.

| Check | Result |
| --- | --- |
| TypeScript + Vite production build | Passed |
| Client/API and selector tests | 8 passed |
| Spring Boot integration tests against H2 | 10 passed; no failures or skipped tests |
| Flyway initial schema + Hibernate validation | Passed during integration tests and real application startup |
| Executable JAR with bundled React assets | Built successfully; bundled files match the frontend build |
| Real HTTP smoke test | Passed |
| File-backed H2 persistence after stopping and restarting the JVM | Passed |

Java tests cover registration, password hashing, incorrect credentials, real CSRF tokens, authenticated CRUD, per-user isolation, optional sample data, request validation, stage updates/removal, pagination/search, missing version headers, stale writes, soft delete and undo. Timestamp precision is normalized to database microseconds so creation timestamps remain stable across round-trips.

The HTTP smoke test launches the executable JAR on loopback using a temporary database and synthetic account. It checks the website and static assets, registers and signs in, creates/updates/deletes/restores a record, rejects a stale update, restarts the JVM, signs in again, verifies the saved record and checks logout. It does not alter user data.

The test-only Mockito configuration uses the subclass mock maker, avoiding JVM agent attachment in restricted environments. The integration tests use real application services, security, controllers, migrations and database operations.

Not verified in this session: automated browser/visual QA, the PostgreSQL/Docker configuration, high-volume load behavior, or a hosted Java deployment. The earlier Sites deployment remains the separate local-data prototype.

Run `npm test`, `npm run build`, and `backend/mvnw -Pwith-ui verify` (on Windows use `mvnw.cmd`). After building the bundled JAR, `python tests/http_smoke.py` repeats the HTTP/restart check using only Python's standard library.
