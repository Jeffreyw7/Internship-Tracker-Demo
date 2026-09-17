# Authentication and Database Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking. Respect the user's current scope: this turn authorizes documentation, not automatic execution of this plan.

**Goal:** Bound authentication abuse and eliminate privileged database credentials from the running web application.

**Architecture:** Use bounded process-local token buckets and a shared nonblocking authentication work semaphore. Run PostgreSQL migrations in a separate one-shot Java process, then start Spring Boot with a restricted runtime role.

**Tech Stack:** Java 17 source compatibility, current Spring Boot/Security and Flyway dependencies, PostgreSQL 17, H2 for existing development tests, React/TypeScript, Docker Compose.

**Spec:** [Approved design](../specs/2026-09-17-authentication-and-database-hardening-design.md).

**Status:** Planned only. No security implementation or new test results are represented by this document.

## Global Constraints

- Implement bounded authentication controls for the current single-instance backend and move PostgreSQL migrations into a separate one-shot process.
- The running application receives only runtime database credentials.
- Retain the existing H2 development workflow.
- Existing data and volumes are preserved.
- Throttled writes are never automatically replayed.
- Runtime is NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS.
- Do not claim PostgreSQL verification from H2 results.
- Do not expand scope into password reset, email verification, shared sessions, or unrelated audit findings.
- The rate and storage defaults are the values in the approved design; test configurations may use smaller limits for deterministic verification.
- Use separate commits for each independently verified deliverable when executing in a development branch. Do not mix unrelated local changes.

## Workstream A: authentication abuse controls

### Task A1: Implement the limiter and work permit contract

**Files**
- Create: backend/src/main/java/com/campus/tracker/auth/abuse/AuthAbuseProperties.java
- Create: backend/src/main/java/com/campus/tracker/auth/abuse/AuthRateLimiter.java
- Create: backend/src/main/java/com/campus/tracker/auth/abuse/AuthWorkLimiter.java
- Create: backend/src/test/java/com/campus/tracker/auth/abuse/AuthRateLimiterTest.java
- Create: backend/src/test/java/com/campus/tracker/auth/abuse/AuthWorkLimiterTest.java
- Modify: backend/src/main/resources/application.yml

**Interfaces**
- AuthAbuseProperties holds validated burst/refill policies, maximum stored keys, and maximum active work.
- AuthRateLimiter has a testable constructor accepting maximumKeys and a java.util.function.LongSupplier monotonic ticker.
- AuthRateLimiter.Policy(int capacity, java.time.Duration refillPeriod) refills capacity tokens during refillPeriod.
- AuthRateLimiter.Decision(boolean allowed, long retryAfterSeconds) represents admission.
- AuthRateLimiter.acquire(String key, Policy policy) returns Decision.
- AuthWorkLimiter(int maximumActive) exposes Permit tryAcquire(); a null result means saturated.
- AuthWorkLimiter.Permit implements AutoCloseable and releases exactly once.

- [ ] Write focused tests before implementation. The following specifies an executable limiter boundary test:

```java
@Test void rejectsUntilRefilled() {
    var nanos = new java.util.concurrent.atomic.AtomicLong();
    var limiter = new AuthRateLimiter(100, nanos::get);
    var policy = new AuthRateLimiter.Policy(2, java.time.Duration.ofMinutes(1));
    assertThat(limiter.acquire("login-ip:127.0.0.1", policy).allowed()).isTrue();
    assertThat(limiter.acquire("login-ip:127.0.0.1", policy).allowed()).isTrue();
    var denied = limiter.acquire("login-ip:127.0.0.1", policy);
    assertThat(denied.allowed()).isFalse();
    assertThat(denied.retryAfterSeconds()).isEqualTo(30);
    nanos.addAndGet(java.time.Duration.ofSeconds(30).toNanos());
    assertThat(limiter.acquire("login-ip:127.0.0.1", policy).allowed()).isTrue();
}
```

- [ ] Run from backend: `./mvnw -Dtest=AuthRateLimiterTest,AuthWorkLimiterTest test`; on Windows use `.\mvnw.cmd`. Record the expected initial missing-class/assertion failure.
- [ ] Implement synchronized admission: refill the selected bucket; reject if depleted; otherwise decrement once. Before adding a new key at capacity, remove only fully refilled buckets; reject if still full. Use ceiling seconds with a minimum of one for denied requests.
- [ ] Add tests that launch concurrent acquisitions against a fixed ticker and assert accepted count equals capacity; storage-pressure tests must show rotating new keys cannot reset an existing depleted key.
- [ ] Implement nonblocking permits with a Semaphore and idempotent close. Test that an exception inside try-with-resources restores capacity and a second close cannot increase capacity.
- [ ] Add property binding validation and the approved defaults. Re-run focused tests, inspect the diff, and commit the tested limiter deliverable.

### Task A2: Enforce limits at Spring boundaries

**Files**
- Create: backend/src/main/java/com/campus/tracker/auth/abuse/AuthAbuseFilter.java
- Create: backend/src/main/java/com/campus/tracker/auth/abuse/LoginWorkFilter.java
- Create: backend/src/main/java/com/campus/tracker/auth/abuse/AuthAbuseConfiguration.java
- Create: backend/src/main/java/com/campus/tracker/auth/abuse/AuthThrottleException.java
- Modify: backend/src/main/java/com/campus/tracker/config/SecurityConfig.java
- Modify: backend/src/main/java/com/campus/tracker/auth/AuthController.java
- Modify: backend/src/main/java/com/campus/tracker/common/ApiErrorHandler.java
- Create: backend/src/test/java/com/campus/tracker/AuthAbuseIntegrationTest.java

**Interfaces**
- Filters consume A1 policies, acquire(), tryAcquire(), and ObjectMapper.
- AuthThrottleException carries retryAfterSeconds for MVC registration saturation.
- Early filter owns endpoint/IP budgets; login filter owns email budget and login work permit.
- Controller owns registration work permit before AccountService.register() is called.

- [ ] Write MockMvc tests for repeated valid-CSRF login requests, using a dedicated test context with small budgets. Assert the final response:

```java
mvc.perform(post("/api/auth/login").with(csrf())
        .with(r -> { r.setRemoteAddr("192.0.2.10"); return r; })
        .param("email", "throttle@example.test").param("password", "incorrect"))
    .andExpect(status().isTooManyRequests())
    .andExpect(header().exists("Retry-After"))
    .andExpect(jsonPath("$.message").value(
        "Too many authentication requests. Please try again shortly."));
```

Seed earlier requests up to the configured test budget. Use a test spy/counter on the authentication boundary to prove the denied request does not execute a password check. Test registration rejection against unchanged user/application counts.

- [ ] Run `./mvnw -Dtest=AuthAbuseIntegrationTest test` and record the initial failure.
- [ ] Wire AuthAbuseFilter before CsrfFilter and LoginWorkFilter after CSRF but before UsernamePasswordAuthenticationFilter. Build each filter exactly once; disable servlet auto-registration for any Filter bean.
- [ ] Match the same login path/method semantics as the authentication filter. Verify trailing-path or encoded variants are either rejected by the firewall or subject to the same guard.
- [ ] Canonicalize email exactly as UserDetailsService does. Reject missing, duplicate, or oversized email parameters with a controlled response before storing identity keys or hashing.
- [ ] Use getRemoteAddr() and ensure configuration does not enable unrestricted forwarded-header rewriting. Tests with forged forwarding headers must still consume the original peer's bucket.
- [ ] Implement registration permit scope in the controller:

```java
var permit = workLimiter.tryAcquire();
if (permit == null) throw new AuthThrottleException(1);
try (permit) {
    return accounts.register(request);
}
```

Map AuthThrottleException to 429 plus Retry-After in ApiErrorHandler. Filter rejections must write the same JSON directly because MVC advice does not handle filter exceptions.

- [ ] Test the CSRF endpoint burst, invalid-CSRF handling, email case/space variants, multiple peer IPs targeting one email, exception permit release, and successful registration/automatic-login flow.
- [ ] Keep legacy tests independent of accumulated limiter state using isolated contexts or explicit test configuration. Do not disable the limiter in its security tests.
- [ ] Run the full backend suite and commit the verified integration deliverable.

### Task A3: Preserve throttling information in the frontend

**Files**
- Modify: src/data/api.ts
- Modify: tests/api.test.mjs
- Modify: docs/API.md

**Interfaces**
- ApiError constructor gains optional retryAfterSeconds?: number, preserving existing two-argument calls.
- refreshCsrf() and request() preserve the existing message/status contract and populate retryAfterSeconds.

- [ ] Add fetch-stub tests for 429 from /auth/csrf and /auth/login. Verify one fetch occurs for a rejected request and no POST is replayed.
- [ ] Run `npm test` and record failures before editing.
- [ ] Implement one shared response-error reader in api.ts. Accept valid integer Retry-After seconds; handle HTTP-date values or ignore invalid values without throwing a secondary exception. Preserve server JSON messages and existing generic fallbacks.
- [ ] Make refreshCsrf() use the same error reader so it does not report throttling as server unavailability. Preserve current session-expiration dispatch behavior.
- [ ] Add the 429 response/header contract to docs/API.md.
- [ ] Run `npm test` and `npm run build`; commit after passing.

## Workstream B: PostgreSQL privilege separation

### Task B1: Add an isolated migration runner and provisioning scripts

**Files**
- Create: backend/src/main/java/com/campus/tracker/migration/MigrationMain.java
- Create: deploy/postgres/provision.sql
- Create: deploy/postgres/runtime-grants.sql
- Create: deploy/postgres/upgrade-existing.sql
- Create: deploy/postgres/verify-runtime.sql
- Create: backend/src/test/java/com/campus/tracker/PostgresPrivilegesIT.java
- Modify: backend/pom.xml

**Interfaces**
- MigrationMain.main(String[] args) is a standalone Java entry point. It reads MIGRATION_DATABASE_URL, MIGRATION_DATABASE_USERNAME and MIGRATION_DATABASE_PASSWORD, runs Flyway, applies runtime-grants.sql, and exits nonzero on any failure.
- It does not invoke SpringApplication or initialize the normal web/JPA context.
- Provisioning consumes secrets through a protected admin process with correctly quoted psql variables or a parameter-safe administrative runner.
- Runtime role/table names are fixed as in the design, not arbitrary untrusted SQL identifiers.

- [ ] Add an opt-in PostgreSQL integration-test profile using the project's managed Testcontainers dependencies and Maven Failsafe. Tests must fail visibly when the profile is requested but PostgreSQL/Docker is unavailable.
- [ ] Create a disposable PostgreSQL instance and provision migrator/runtime roles. Assert existing unrestricted credentials fail the expected least-privilege contract before provisioning the fix.
- [ ] Implement MigrationMain using the already managed Flyway API:

```java
Flyway.configure()
    .dataSource(migrationUrl, migrationUser, migrationPassword)
    .locations("classpath:db/migration")
    .load()
    .migrate();
```

Read required environment values without logging them; reject missing values. Package the grants script as a resource available to this runner and execute it transactionally using JDBC after successful migration. Never start the application if either operation fails.

- [ ] Create roles with the approved attributes and apply these explicit grants only after schema migration:

```sql
GRANT CONNECT ON DATABASE campus TO campus_runtime;
GRANT USAGE ON SCHEMA public TO campus_runtime;
GRANT SELECT, INSERT ON TABLE public.campus_users TO campus_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE public.applications TO campus_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.interview_stages TO campus_runtime;
REVOKE ALL ON TABLE public.flyway_schema_history FROM campus_runtime;
```

Provisioning must also remove runtime ownership, privileged memberships, and inherited PUBLIC CREATE/TEMP privileges for the isolated application database. REVOKE from runtime alone does not remove access inherited through another role or PUBLIC.

- [ ] Implement upgrade-existing.sql as an explicit transaction for the known database objects. Validate expected objects and ownership first; do not run broad REASSIGN OWNED across unrelated objects. Do not delete or reinitialize the volume.
- [ ] Implement verify-runtime.sql with queries for role flags/memberships/ownership and effective table/database/schema privileges. Treat expected denial statements as separate JDBC transactions/savepoints so one failure does not mask later assertions.
- [ ] Include executable JDBC assertions:

```java
try (var c = java.sql.DriverManager.getConnection(url, "campus_runtime", password);
     var s = c.createStatement()) {
    assertThatThrownBy(() -> s.execute("CREATE TABLE public.forbidden_probe(id int)"))
        .isInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(() -> s.execute("SELECT * FROM public.flyway_schema_history"))
        .isInstanceOf(java.sql.SQLException.class);
    assertThatThrownBy(() -> s.execute("SET ROLE campus_migrator"))
        .isInstanceOf(java.sql.SQLException.class);
}
```

Require permission-denied SQLSTATE where applicable, not merely any connection or syntax failure. Add positive SELECT/INSERT/UPDATE and stage DELETE assertions in disposable transactions.

- [ ] Run `./mvnw -Ppostgres-it verify`; record unavailable infrastructure as blocked verification, never as a passing result.
- [ ] Commit only after positive operations and negative privileges have been demonstrated on PostgreSQL.

### Task B2: Wire deployment and verify existing-volume transition

**Files**
- Modify: Dockerfile
- Modify: compose.yaml
- Modify: .env.example
- Modify: backend/src/main/resources/application-prod.yml
- Modify: README.md
- Modify: VALIDATION.md
- Modify: backend/src/test/java/com/campus/tracker/PostgresPrivilegesIT.java

**Interfaces**
- The migration container receives only migration secrets; database provisioning receives admin secrets.
- The web container receives only DATABASE_URL, DATABASE_USERNAME=campus_runtime, and its runtime secret.
- The web production profile has spring.flyway.enabled=false and retains Hibernate validation.
- Compose app dependency requires migration service_completed_successfully.

- [ ] Add a migration build target or explicit Java classpath launch that executes MigrationMain with the packaged dependencies. Verify the default web entry point is not invoked.
- [ ] Configure separate admin, migration, and runtime secret variables in .env.example with placeholders, never real credentials. Ensure web service environment/mounts do not expose the privileged secrets.
- [ ] Add successful-migration gating:

```yaml
depends_on:
  database:
    condition: service_healthy
  migrate:
    condition: service_completed_successfully
```

Keep the existing loopback application binding. Do not silently turn the local Compose demo into a public deployment.

- [ ] Run `docker compose config --quiet` to validate structure without printing resolved secrets.
- [ ] Test a fresh isolated stack: migrate, start web, register with samples, login, create/edit/delete/restore, and remove an interview stage.
- [ ] Test an isolated restored legacy database with existing records and original migration history: apply upgrade-existing.sql, run migration, switch credentials, verify record counts/values and all workflows.
- [ ] Deliberately fail a migration in a disposable test fixture and confirm the app does not start.
- [ ] Verify runtime cannot create roles/databases/schemas/tables, create temporary tables, alter/drop/truncate application tables, delete users/applications, or access migration history.
- [ ] Document backup/restore validation, ownership inventory, explicit existing-volume upgrade, secret rotation and rollback in README.md. Record only actually executed checks in VALIDATION.md.
- [ ] Run backend unit/integration suites, PostgreSQL profile, `npm test`, and `npm run build`. Commit the verified deployment deliverable.

## Review and release gates

- [ ] Inspect the final diff for unrelated changes, secret exposure, accidental API changes, and grants broader than the design.
- [ ] Challenge one alternate authentication representation and one legitimate workflow.
- [ ] Perform the security-fix skill's independent read-only bypass/regression review when executing the implementation.
- [ ] Re-run relevant checks after confirmed review fixes.
- [ ] Report authentication verification and PostgreSQL verification separately.
- [ ] Do not label the database issue fixed without real PostgreSQL evidence.
- [ ] Keep public deployment pending until trusted proxy handling, upstream request limits, HTTPS, and load measurements are explicitly configured.

## Documentation verification record

This plan and the linked design document describe approved future work. Source implementation, new tests, database role changes and deployment are not performed by documenting them.

