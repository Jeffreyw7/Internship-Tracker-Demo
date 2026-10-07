# Authentication abuse controls and database privileges: approved design

Date: 2026-09-17
Status: Implemented and locally verified. Docker deployment verification remains outstanding; see ../../../VALIDATION.md.
Scope: Authentication abuse controls and PostgreSQL privilege separation for Campus Internship Tracker.

## Decision

Implement bounded authentication controls for the current single-instance backend and move PostgreSQL migrations into a separate one-shot process. The running application receives only runtime database credentials. Retain the existing H2 development workflow.

These changes reduce automated guessing, authentication CPU exhaustion, registration spam, and the consequences of a compromised database credential. They do not guarantee prevention of distributed denial of service or protect all tenants from a compromised application runtime.

## Current evidence

- `backend/src/main/java/com/campus/tracker/config/SecurityConfig.java` configures BCrypt cost 12 and Spring form login at POST /api/auth/login. Login bypasses MVC AuthController.
- `auth/AccountService.java` creates accounts inside a transaction and can seed ten applications.
- Login identity normalization is trim().toLowerCase(Locale.ROOT).
- GET /api/auth/csrf uses HttpSessionCsrfTokenRepository and creates session state.
- `src/data/api.ts` registration calls registration, automatic login, and three CSRF fetches in the successful flow.
- `compose.yaml` initializes PostgreSQL with POSTGRES_USER=campus and gives that same credential to the app. On a fresh volume, this is the image's initialization superuser.
- Flyway currently uses the application datasource. Hibernate validates the schema.
- All schema primary keys are generated UUIDs; no sequence privileges are needed.
- The preceding audit ran 13 backend integration tests successfully against H2. Those tests did not validate these proposed controls.
- Docker and psql were not found on the current command path during planning. Real PostgreSQL verification remains required.

## Alternatives and tradeoffs

| Approach | Benefit | Limitation | Decision |
| --- | --- | --- | --- |
| Proxy-only authentication limits | Cheap rejection before Java | Cannot reliably enforce canonical account limits; direct backend paths may bypass it | Add as a production defense, not the sole control |
| Bounded local counters plus authentication work cap | Small deployment change; testable within existing Spring app | Counters reset at restart and do not coordinate replicas | Implement for the current single-instance app |
| Shared atomic counters, for example Redis | Coordinates replicas and survives application restarts | Additional service, credentials, outage policy, and operational work | Required before multi-instance deployment |
| Separate Flyway credentials inside web app | Easy configuration | Privileged credentials remain accessible to a compromised web process | Do not use for production |
| Separate migration job and runtime role | Removes privileged credentials from web process | Adds a deployment step | Implement |

## Authentication invariants

1. Untrusted requests are budgeted before password verification or registration transactions.
2. Matching must cover the actual Spring login endpoint; controller-only enforcement is insufficient.
3. Existing CSRF validation, authentication behavior, and per-account application ownership remain intact.
4. Account keys use exactly the authentication normalization and are bounded before storage.
5. Existing and nonexistent accounts receive the same limiter treatment.
6. Concurrent requests cannot race past limits; a permit is released on every exit.
7. Attacker-controlled identities cannot grow memory without bound or evict active restrictions.
8. Only trusted network information determines client identity.
9. Throttled writes are never automatically replayed.

### Initial configuration

These are starting defaults, not load-tested capacity promises. Use token buckets: burst capacity and continuous refill are distinct.

| Bucket/control | Burst capacity | Refill |
| --- | --- | --- |
| Login per peer IP | 20 | 20 per minute |
| Login per normalized email | 10 | 1 per minute |
| Registration per peer IP | 3 | 3 per hour |
| CSRF endpoint per peer IP | 60 | 60 per minute |
| Combined active login/registration work | 2 | Permit released when request work ends |
| Limiter key storage | 10,000 entries total | Reclaim only entries whose restrictions have fully expired |

Configuration values must be positive and internally valid. Tune the work cap to measured BCrypt and database capacity. Shared campus or corporate NAT addresses may need higher IP limits; retain identity and global work limits.

### Enforcement boundaries

- An early security filter applies peer-IP limits before CSRF can create state. The shared CSRF budget covers token GET/HEAD and all anonymous unsafe requests, including unmapped paths and incorrect methods. Login and registration also consume their own IP budgets.
- A login guard after CSRF and before UsernamePasswordAuthenticationFilter applies the email limit and tries to acquire a shared work permit.
- Registration acquires the same work permit in AuthController after validated JSON binding and before invoking the transactional AccountService. Do not consume the JSON body in a servlet filter.
- Hold permits only through authentication/registration work and always release using try/finally or AutoCloseable.
- The early IP budget and downstream identity budget are separate layers; admission is atomic within each bucket. A downstream rejection does not refund the upstream request budget.
- Use the same request matching semantics as Spring's login filter. Verify path variants accepted by the security firewall cannot bypass throttling.
- Register each custom filter only once in the security chain. If exposed as a Spring Filter bean, disable its automatic servlet registration.

### Limiter storage and time

Use a monotonic injectable time source. Synchronize bucket lookup, refill, admission, and insertion so concurrency and capacity decisions are atomic. Never sleep request threads.

Reclaim a key only when its bucket is fully replenished: removing a still-restricted key resets its budget. When storage is full after reclamation, reject unknown keys temporarily; continue enforcing existing buckets. An LRU cache that evicts active entries is not acceptable.

Validate email key length before allocating state; the existing account model caps emails at 254 characters. Empty, oversized, or ambiguous duplicate login parameters must receive a controlled error before password verification. Do not apply the new registration password-complexity rules to legacy-account login.

A nonblocking semaphore bounds active login and registration work. Saturation returns 429 with a short retry interval; it does not enqueue unlimited requests.

### IP identity and proxy deployment

Use request.getRemoteAddr() in the default deployment and explicitly avoid untrusted forwarded-header interpretation. A reverse proxy deployment must restrict direct backend access and configure trusted proxy handling; otherwise all users may share the proxy bucket or attackers may spoof bucket keys.

IPv6 address rotation and botnets limit what per-IP controls can accomplish. Account limits and the global work cap remain necessary. Public deployment also needs upstream connection, request-size, and traffic controls.

### Responses and frontend

Throttling returns HTTP 429, a positive integer Retry-After header in seconds, and the existing JSON message shape:

```json
{"message":"Too many authentication requests. Please try again shortly."}
```

For token depletion, calculate the wait until a token is available. For work saturation, use a short retry hint without promising capacity at that time.

Extend ApiError with an optional retryAfterSeconds value. Both refreshCsrf() and request() must preserve 429 messages and parse Retry-After. The UI should show the message and retry delay without replaying any POST. Other existing error and session-expiration behavior remains intact.

Emit aggregate rejection counts and work utilization without logging passwords, session cookies, CSRF tokens, or raw account identifiers as metric labels.

## PostgreSQL security invariants

- The web process has no admin or migration credential.
- Runtime is NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS.
- Runtime owns no database, schema, table, or migration-history object and has no privileged role memberships.
- Runtime receives only effective privileges needed by existing workflows; inherited PUBLIC privileges must also be inspected.
- Migrations finish successfully before the application starts.
- Failed migrations stop rollout; do not silently start against an unknown schema.
- Existing data and volumes are preserved.

### Roles

| Role | Responsibility |
| --- | --- |
| campus_admin | Provisioning/maintenance only; not supplied to application |
| campus_migrator | Owns and changes application schema and migration history; used only by the migration job |
| campus_runtime | Application data operations only |

Keep the existing public schema to avoid an unrelated schema relocation. Restrict PUBLIC CREATE on that schema and inspect database CREATE/TEMP privileges. Runtime has CONNECT on campus and USAGE on public, but cannot create persistent or temporary objects.

### Runtime table privileges

| Table | Privileges |
| --- | --- |
| campus_users | SELECT, INSERT |
| applications | SELECT, INSERT, UPDATE |
| interview_stages | SELECT, INSERT, UPDATE, DELETE |
| flyway_schema_history | None |

Stage deletion is necessary for JPA orphan removal. Application deletion is currently a Boolean update. Account updates/deletions are absent and should not receive advance privileges. Future features must deliberately revise the grant contract.

Use explicit grants after migration. Do not grant every future table by default: that could expose migration history or a future sensitive table. No sequence grants are required by the current schema.

### Migration deployment

Provision roles using secrets supplied outside source control. Use separate secret values for administrator, migrator, and runtime. Do not pass secrets in command arguments, print them, or interpolate them into unquoted SQL.

Compose should run: database health check -> migration job -> web application. The migration job runs the repository's existing migrations and a separate PostgreSQL grants script. Use a pinned runner compatible with the project's Flyway version. The web production profile disables Flyway but retains Hibernate ddl-auto=validate.

The migration runner must not launch web endpoints or execute normal JPA startup. A dedicated Java main using Flyway's API allows reuse of the existing Maven dependencies without an additional unversioned CLI installation.

The local H2 profile retains existing in-process migrations. PostgreSQL role SQL must not enter the shared H2-compatible migration directory.

### Existing-volume rollout

1. Take a database backup and restore it in an isolated validation environment.
2. Inventory database/schema/table ownership, PUBLIC grants, role memberships, and effective permissions.
3. Provision the new roles without resetting or dropping existing data.
4. Transfer ownership of the known application objects and migration history to campus_migrator; preserve an administration path.
5. Apply explicit grants and verify them with runtime credentials.
6. Run migrations and then switch the application to campus_runtime.
7. Exercise registration with samples, login, application CRUD/restore, and stage removal.
8. Remove the former privileged credential from app configuration; rotate/retire old application credentials deliberately.

Initialization scripts only affect empty PostgreSQL data directories. Existing installations require this explicit transition. Never use docker compose down -v as a remediation step.

Rollback should restore compatible application configuration or use the verified backup when schema compatibility requires it. Stop a failed rollout rather than restoring superuser credentials to the web process.

## Acceptance criteria

Authentication: token exhaustion and refill; email normalization; IP/account rotation; forwarded-header spoofing; bounded storage; atomic concurrent admission; permit release on failures; invalid CSRF; registration/login/CSRF flow; no automatic client replay.

PostgreSQL: inspect role flags, memberships and ownership; positive registration/login/CRUD/sample/stage workflows; deny DDL, TRUNCATE, user/application DELETE, migration-history access, role/database creation and SET ROLE to privileged roles; verify fresh and existing-volume paths; failed migration blocks web startup.

Run existing Java and frontend suites after focused tests. Do not claim PostgreSQL verification from H2 results.

## References

- [OWASP authentication guidance](https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html)
- [Spring Security filter architecture](https://docs.spring.io/spring-security/reference/servlet/architecture.html)
- [PostgreSQL 17 privileges](https://www.postgresql.org/docs/17/ddl-priv.html)
- [PostgreSQL 17 schemas](https://www.postgresql.org/docs/17/ddl-schemas.html)
- [Official PostgreSQL image initialization](https://hub.docker.com/_/postgres)

## Implementation plan

See [the implementation plan](../plans/2026-09-17-authentication-and-database-hardening.md).
