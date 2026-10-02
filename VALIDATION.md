# Validation record

## Password lifecycle validation — October 2, 2026

Validated with Java 21.0.12.1, Node 25.2.0, Maven wrapper and Spring Boot 3.5.16. Tests use synthetic accounts, isolated temporary/file-backed or in-memory H2 databases, and a loopback SMTP sink; no normal application data or production mail was used.

| Check | Result |
| --- | --- |
| Client/API/password tests (`npm test`) | 15 passed |
| Strict TypeScript + Vite production build | Passed |
| Backend (`backend/mvnw.cmd -Pwith-ui verify`) | 40 passed; no failures/skips; bundled executable JAR built |
| Flyway V1/V2 and Hibernate schema validation | Passed during tests and real startup/restarts |
| Python bundled CRUD/password/SMTP/restart smoke | Passed |
| Node bundled HTTP/password/SMTP/restart smoke | Passed |
| Encrypted email queued before JVM termination, delivered after restart | Passed |
| Browser recovery and initiating-session password change | Passed with synthetic email links |
| Desktop/mobile visual inspection | Passed at 1264px and 390px; no horizontal overflow on completion |
| Keyboard return, invalid links, immediate fragment removal | Passed |
| Pending login followed by link navigation/dismissal | Passed; stale response ignored, sign-in remains enabled |
| Lost response after committed password change | Passed; controls disabled, no replay, new-password login works |
| Security review | All 37 changed source-like files reviewed; one quota availability finding fixed and regression verified |

Backend coverage includes generic unknown-email responses, CSRF, authenticated change initiation, wrong-password/account/session/purpose rejection, expiry boundaries, weak-password retry, atomic replay/concurrency handling, rollback, fresh credential versions during concurrent issuance, ordered session bindings, two-session revocation, encrypted payload/AAD checks, durable/concurrent limits, notification enqueueing, worker claim exclusivity, expired lease recovery, retry/exhaustion and terminal payload erasure. Missing accounts and failed version lookups cannot authorize API requests.

The security scan's original schema-invalid draft submission did not create a manifest. Corrected submission and finalization completed the same scan, `6a0c7348-441b-4eba-829f-01db6a7c6609`. Its sealed pre-fix snapshot retains a medium finding: unknown/throttled requests consumed the shared delivery quota. The subsequent fix reserves global capacity only for eligible deliveries; a regression first failed, then passed. Independent review approved the correction. The sealed report is an immutable pre-fix observation, not a clean rescan of later corrections. Later token-memory cleanup and H2 durability changes received targeted tests/review.

Abrupt Windows JVM termination initially exposed H2's delayed-write window: recently acknowledged challenge/outbox rows disappeared. Local H2 URLs now use `WRITE_DELAY=0`; both real restart tests passed afterward. The SMTP test helpers decode the declared MIME encoding rather than interpreting unencoded link characters as quoted-printable escapes.

Frontend tests also verify that the initial module-held token is released when a flow is dismissed. Browser screenshots are under ignored `test-results/`: `password-recovery-desktop.png`, `password-completion-mobile.png`, `password-security-mobile.png`, `password-uncertain-mobile.png`, and `password-invalid-desktop.png`.

Not verified: PostgreSQL migration/concurrency, production SMTP/provider credentials, high-volume load, multi-instance shared sessions, cross-tab synchronization or hosted deployment. PostgreSQL/Docker tools were unavailable. Mail remains disabled until the environment variables documented in README.md are supplied. In-memory sessions require sticky routing or a shared session store for multiple instances; already authorized requests are not cancelled by credential-version changes.

Reproduce from the root with `npm test`, `npm run build`, and from `backend/` with `.\mvnw.cmd -Pwith-ui verify`. Then run `node tests/password_http_smoke.mjs` and `python tests/http_smoke.py`. Use `--serve` on the Node helper only for isolated browser verification; its test links and credentials are written to ignored `test-results/`.

## Previous validation — September 15, 2026

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

Not verified in the September 15 run: automated browser/visual QA, the PostgreSQL/Docker configuration, high-volume load behavior, or a hosted Java deployment. The earlier Sites deployment remains the separate local-data prototype.

Run `npm test`, `npm run build`, and `backend/mvnw -Pwith-ui verify` (on Windows use `mvnw.cmd`). After building the bundled JAR, `python tests/http_smoke.py` repeats the HTTP/restart check using only Python's standard library.
