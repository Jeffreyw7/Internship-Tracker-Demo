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

## Security implementation verification — September 17, 2026

Implemented authentication IP/account budgets, bounded anonymous CSRF-session allocation, a shared authentication work cap, 429/Retry-After client handling, and PostgreSQL provisioning/migration/runtime role separation.

| Check | Result |
| --- | --- |
| Backend unit and H2 security/workflow integration tests | 31 passed; zero failures/errors/skips |
| PostgreSQL privilege integration tests | 2 passed against isolated PostgreSQL 17.6 clusters; zero failures/errors/skips |
| Client tests | 12 passed |
| TypeScript/Vite production build | Passed |
| Executable JAR with bundled UI | Built during Maven verify |
| Real HTTP/H2 restart smoke test | Passed, including confirmed JVM shutdown |
| Independent source review | Confirmed anonymous unsafe-request session bypass; fixed and scoped re-review found no concrete regression |
| Docker image build, Compose configuration execution and migration-failure startup gating | Not executed: Docker unavailable |
| Production load/capacity validation | Not executed; limits are initial single-instance defaults |

Commands executed from the repository root:

```powershell
npm test
npm run build
python tests/http_smoke.py
```

Combined Maven verification executed from backend with locally extracted PostgreSQL test binaries:

```powershell
.\mvnw.cmd -B '-Ppostgres-it,with-ui' '-Dpostgres.test.bin=C:/Users/jeffr/Internship-Tracker-Demo/backend/target/postgres-verification/edb/pgsql/bin' verify
```

The postgres-it profile normally uses Docker/Testcontainers. Its optional Windows binary mode initializes its own databases under backend/target and cannot target an existing application database. Downloaded binaries and synthetic test clusters are ignored build outputs, not repository dependencies.

Both PostgreSQL scenarios exercised exact provisioning/upgrade scripts, repeat migrations, quoted test passwords, retained legacy data, effective role attributes/ownership, allowed data operations, and SQLSTATE 42501 for prohibited administrative operations. Each also started Spring Boot under the production profile with only runtime credentials and exercised real HTTP registration with samples, login, listing, creation, updates, stage removal, soft-delete/restore, and logout.

Regression evidence: missing authentication guards initially returned 401/201 where 429 was expected; client tests initially lost Retry-After and misreported CSRF throttling. A later anonymous unsafe-path test initially returned 403 and created a session beyond the intended budget; it now returns 429 without a session. Real PostgreSQL tests caught an unsupported Flyway logger setting, which was corrected before both scenarios passed.

The Windows PostgreSQL harness now redirects subprocess output to files and uses a timeout. The HTTP smoke test now resolves the actual Java runtime instead of terminating only Oracle's PATH launcher; it verifies the listening port closes before restart.

Security status: implementation and local verification complete. Docker rollout verification remains outstanding. No user database upgrade, public deployment, or remote push was performed. Existing PostgreSQL installations require the explicit procedure in docs/DATABASE-SECURITY.md.
