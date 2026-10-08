# Operations foundations

`GET /livez` reports process liveness. `GET /readyz` reports readiness including database access; a failed database removes readiness while liveness remains healthy. Both disclose aggregate status only. `/api/health` remains the launcher marker and is not a dependency-aware readiness check.

`GET /actuator/prometheus` requires `Authorization: Bearer <METRICS_TOKEN>`. An unset token disables scraping. Normal account sessions never grant management access. Other actuator routes are denied. Configure the scraper secret outside Git and block public ingress to `/actuator/`; private networking and the token serve separate purposes. Do not attach account IDs, email addresses, raw paths, search text, or credentials to metric labels.

API responses use `Cache-Control: no-store`. Safe request IDs of 1-64 ASCII letters, digits, underscores or hyphens are accepted; other values receive generated UUIDs. The `X-Request-ID` response header corresponds to the request's logging context. Bodies and credentials are not logged by this filter. Database connection failures return safe JSON 503 and `Retry-After: 3` if a response has not already committed. Clients must not automatically replay writes.

## Resource budgets

Defaults: 10 connections per process, 2 idle connections, 3-second acquisition and 2-second validation deadlines. Production PostgreSQL defaults add a 3-second connection deadline, 10-second socket deadline, 8-second statement deadline and 2-second lock deadline. Web defaults bound Tomcat to 100 request threads and 100 pending connections, with a 5-second connection timeout and 15-second keep-alive timeout. Shutdown drains for 30 seconds. These are starting budgets to measure, not throughput guarantees.

Environment overrides: `DATABASE_POOL_SIZE`, `DATABASE_POOL_MIN_IDLE`, `DATABASE_ACQUIRE_TIMEOUT_MS`, `DATABASE_VALIDATE_TIMEOUT_MS`, `DATABASE_CONNECT_TIMEOUT_SECONDS`, `DATABASE_SOCKET_TIMEOUT_SECONDS`, `DATABASE_STATEMENT_TIMEOUT_MS`, `DATABASE_LOCK_TIMEOUT_MS`, `HTTP_MAX_THREADS`, `HTTP_MIN_THREADS`, `HTTP_ACCEPT_COUNT`, `HTTP_CONNECTION_TIMEOUT`, `HTTP_KEEP_ALIVE_TIMEOUT`.

Before selecting autoscaling limits, enforce:

```text
(maximum API instances + rolling-deployment overlap) × API pool
  + mail worker instances × mail pool
  + export worker instances × export pool
  + migrations/maintenance/monitoring reserve
  <= database's available connection budget
```

For example, 12 API instances plus 2 overlapping replacements at 10 connections, 4 mail workers at 5, 4 export workers at 5, and a 20-connection operational reserve require 200 connections. Database capacity must also reserve provider/admin connections. Check pool wait, statement latency and worker contention under load before increasing these values. Worker separation, distributed dependency probes, private ingress, migration ordering and recovery procedures are implemented below. Their local qualification does not establish hosted capacity or failover.

## Shared sessions (opt-in)

Activate the `distributed` Spring profile alongside the database profile. Local container sessions remain the default. Redis sessions use HttpOnly/SameSite=Lax `JSESSIONID`, the configured Secure-cookie policy, and a two-hour idle timeout. No load-balancer affinity is required. Login stores a sanitized principal before Spring Security saves the context; the session never needs a password hash or submitted password. The serializer's version 1 supports only explicit scalar metadata, CSRF tokens, and ordinary-user security contexts. Adding session attributes or changing that format requires compatibility tests and a coordinated namespace/version rollout.

Configure `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_TLS`, and a deployment-specific `SESSION_NAMESPACE`. Production distributed startup rejects missing Redis credentials or the local namespace. Redis has two-second connection/command deadlines. Redis is a required readiness dependency in this profile; SMTP remains excluded. Redis unavailability or invalid serialized session data returns 503, without a container-session fallback. Expired/missing sessions remain unauthenticated.

Use the complete `compose.scalable.yaml` topology below for local replicated Redis sessions/limits, shared private storage and dedicated mail/export workers. Regular `compose.yaml` is a single-host PostgreSQL setup, with local sessions, embedded mail delivery and a restricted export worker sharing a private file volume; it does not offer the distributed shortcut. Alert on Redis memory approaching the ceiling, write failures, session errors and readiness loss. Session TTLs bound retention. Deleting the session namespace intentionally signs out all accounts; do so only under an approved incident/recovery procedure.

Run real-service checks with `./mvnw -Pdistributed-it -Dit.test=DistributedSessionIT verify` from `backend/`. The test owns a disposable Redis container and starts two HTTP application contexts against an isolated database. If Docker is unavailable, these checks fail visibly. A supplied `-Dcampus.test.redis.port=<disposable-loopback-port>` can qualify routing/restart against an isolated local Redis instance; container-stop outage checks still require an owned container.

## Shared authentication admission

Distributed mode uses an atomic Redis token bucket rather than a per-process bucket. All API instances must use the same `AUTH_ADMISSION_NAMESPACE` and `AUTH_ADMISSION_HMAC_SECRET` (at least 32 characters). Keys contain HMAC identifiers rather than email/IP plaintext. The cardinality index and bucket share a Redis Cluster hash slot. Cleanup visits at most 64 expired entries per admission; active restrictions are never evicted to make room. Bucket TTLs expire only when tokens would be fully replenished. New identities are denied with a positive Retry-After when the configured key ceiling is reached.

Keep Redis's eviction policy `noeviction`: write failures, timeouts, malformed replies and policy mismatch return safe 503; they never become successful admissions or misleading 429 quota denials. Namespace cardinality defaults to 10,000 keys (`AUTH_MAXIMUM_KEYS`), and per-process expensive password work remains capped at two (`AUTH_MAXIMUM_ACTIVE`) across login, registration, resend and confirmation. Qualify these budgets against shared-NAT traffic and login throughput. An admission-store outage can affect existing sessions too when sessions and admission share one Redis service; separate service placement can be introduced if isolation is required.

Coordinate changes to bucket policies across instances. The Lua script fails closed on an incompatible active bucket instead of resetting its restriction during a rolling deployment. Plan namespace/secret rotations deliberately: changing either abandons old quota state. Source: [Redis Lua scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/); the script accesses only its two declared keys.

Verification counters remain committed database fixed windows. `VERIFICATION_GLOBAL_MAIL_DAILY_LIMIT` defaults to 1,000 and is configurable; changing it should track mail-provider capacity and abuse monitoring. Existing per-recipient and peer-IP windows remain enforced.

Local forwarded headers remain ignored (`NONE`). Distributed mode uses Tomcat native handling with no trusted forwarding peers by default. `TRUSTED_PROXY_REGEX` must match the private ingress's actual peer addresses; do not configure every private network or the whole Internet as trusted. Ingress must strip user-supplied forwarded headers and prevent direct API access. Tests cover untrusted headers, trusted client separation, spoofed leftmost hops, and shared-NAT budgets. A first-party gateway's routing configuration and trust range must be reviewed together.

## Dedicated mail workers and key rotation

Use the Dockerfile's `worker` target, which runs `com.campus.tracker.worker.WorkerApplication` with a non-web context. Select `WORKER_ROLE=mail` and the `worker,prod` profiles; supply only the `campus_mail_worker` database credential. The export role uses `WORKER_ROLE=export`, the `worker,prod,export-worker` profiles, and its own database/storage credentials. The worker imports no HTTP controllers, session store, security filters or JPA entities.

The worker pool defaults to two delivery threads (`MAIL_WORKER_CONCURRENCY`, maximum 32) and five database connections (`WORKER_DATABASE_POOL_SIZE`). Its executor has no task queue. Claims happen only inside an available thread, one job at a time; SMTP holds no database transaction. PostgreSQL uses a short `FOR UPDATE SKIP LOCKED` claim transaction. Leases last two minutes; completion/retry requires the current unexpired owner. A crashed/stalled worker can cause redelivery after lease expiry. Stable Message-ID and single-use confirmation tokens remain intact; provider acceptance followed by a crash cannot be promised exactly once.

Delivery polling and maintenance have separate schedules. Maintenance changes/deletes at most 100 records per batch across all categories, rather than sweeping all rows on every delivery poll. Cleanup never deletes user/application records. Before a new worker deployment, provision its restricted role and run the migrator's grant script; see [database privilege separation](../docs/DATABASE-SECURITY.md).

Encrypt new jobs with `OUTBOX_KEY_ID`/`OUTBOX_ENCRYPTION_KEY`. Retain earlier decrypt-only keys explicitly in protected configuration under `campus.mail.decryption-keys`. Preserve each exact key ID with bracketed map keys if needed:

```yaml
campus:
  mail:
    decryption-keys:
      "[previous-key-id]": ${RETIRED_OUTBOX_ENCRYPTION_KEY}
```

Never put real key material into committed configuration. At most seven retired keys plus the active key are accepted. Roll out the decrypt overlap before changing the active encrypt key, verify worker compatibility, then switch producers. Retire the old key only after all queued/in-flight jobs using it have drained, expired or been cancelled and any recovery window has been considered. Removing it early makes those jobs fail safely and erases their payloads; it does not send corrupt email. Protect keys with the database backups so recovery can decrypt retained jobs.

## Portable replicated qualification

`compose.scalable.yaml` runs two Java 17 API instances, a static Nginx gateway, PostgreSQL, authenticated Redis, private SeaweedFS storage, SMTP capture, and separate mail/export workers. Only the gateway is published, on loopback. Database, Redis, storage, mail, workers and API ports have no host bindings. The data network is internal; only the gateway and APIs share the edge network. This single-host fixture does not establish multi-zone availability.

Run `npm run test:distributed` with Docker Desktop's Linux engine running. The harness creates a uniquely named `campus-test-<UUID>` project, unused subnet, temporary port and synthetic secrets. It builds the images, exercises actual HTTP routing, mail/export processing, restart and Redis outage/memory failure, injects a disposable bad migration, then removes only its own containers/networks/volumes. Redacted logs stay under `backend/target/campus-test-<UUID>/`. It never reuses the regular Compose database or existing application data. On Windows, a newly installed Docker CLI may require reopening the terminal; `CAMPUS_DOCKER_EXECUTABLE` can point to its absolute path and the harness makes its credential helper available for that process.

For manual use, supply every required variable shown in `.env.example`, a private storage identity file, and a new explicit Compose project name. Use `docker compose -f compose.scalable.yaml -p <new-project> config` privately to inspect the resolved configuration: its output contains secrets. Start only the database and migrator before upgrading an existing installation; follow the database security procedure. Never attach an old volume to this qualification file to bypass its empty-database provisioning guard.

The one-shot migrator applies Flyway and packaged runtime grants. APIs and both workers depend on `service_completed_successfully`; an invalid migration or missing role prevents their startup. The gateway waits for both API readiness probes. Dependency readiness is an aggregate, private-detail-free DB/Redis check. Nginx's passive peer handling and Docker health checks do not replace a hosted orchestrator's active readiness removal and draining. Stop grace is 35 seconds, exceeding Java's 30-second drain. Gateway downstream deadlines are shorter. Failed writes are never replayed onto another peer; callers resolve uncertain outcomes through fresh reads.

The gateway strips supplied `Forwarded` and replaces `X-Forwarded-*` from the actual connection. `TRUSTED_PROXY_REGEX` matches only its assigned address; update subnet, address and regex together. Do not trust all private addresses. Cookie/CSRF/If-Match headers and query strings pass through. API responses are uncached. Hashed `/assets/` files are immutable; HTML, bootstrap and other static paths revalidate. `/actuator` is blocked publicly. The local `X-Campus-Instance` diagnostic exposes a private peer address for routing tests; remove it at the hosted edge. Its HTTP/non-secure-cookie, plaintext private-storage and capture-mail settings are solely for this loopback fixture. Hosted deployment requires HTTPS, secure cookies, Redis TLS, private authenticated storage, real SMTP policy and managed secrets.

The fixture caps API pools at 6 each and worker pools at 3 each: 18 steady connections, 24 with one API replacement overlapping, plus operational reserve within PostgreSQL's 60-connection ceiling. It sets separate CPU/memory/process limits and one export at a time per worker. These are measurement starting points, not account-count or throughput promises. Redis has 64 MiB and `noeviction`; sessions/admission write failure returns 503. Its AOF every-second persistence can lose recent session changes after a crash: require hosted logout/failover qualification, and deliberately invalidate sessions after uncertain recovery. Version tags are pinned; CI records actual immutable image IDs/registry digests, and deployments must promote the tested digests.

## Private durable CSV exports

The API commits an owner-scoped job before returning 202. Each account may have two unexpired pending/running jobs; READY jobs no longer occupy generation capacity and remain downloadable until expiry. An isolated export worker reads at most 100 applications and 3,000 stages per page using immutable creation-time/ID traversal bounded by the job's creation cutoff. This is a live export: later edits/deletions can affect values; it is not a historical database snapshot. Output includes the entire eligible history, notes and all stages, with UTF-8 BOM, CRLF, quotes and spreadsheet formula protection.

Jobs lease for two minutes, renew between pages and every 30 seconds, and require current ownership for publication. Size is capped at 100 MiB, generation at 20 minutes, retention at 24 hours, and transient failures retry at most three attempts. Cleanup batches are bounded. Upload intent is durable before external publication; ambiguous completion preserves potentially READY output until database-authorized cleanup can decide. Cancel/expiry never allow a stale worker to publish READY. A kill after upload can leave a private object; another worker takes over after the lease and maintenance removes the abandoned object after its grace period.

Use `EXPORT_STORAGE=s3`, bucket/region settings and an optional HTTPS-compatible endpoint. Reuse one SDK client with bounded connections/timeouts. API storage credentials allow only private reads; worker credentials permit bucket-scoped write/delete/read/list, never public access. Hosted AWS credentials should come from role identity, not application files. Filesystem mode is for single-host local setups and is rejected in distributed mode. Ordinary H2 startup enables embedded export processing by default; production/distributed API profiles disable it. Regular PostgreSQL Compose provides a separate restricted worker and a shared private volume, mounted read-only in the API. Downloads remain same-origin, authenticated and owner checked; four simultaneous downloads per API are admitted by default. No public or presigned URL is returned. The worker's spool directory must be writable by UID 10001 and ephemeral/private. Retain API and worker schema compatibility during rollouts and keep privileges separate.
