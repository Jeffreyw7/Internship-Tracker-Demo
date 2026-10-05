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

For example, 12 API instances plus 2 overlapping replacements at 10 connections, 4 mail workers at 5, 4 export workers at 5, and a 20-connection operational reserve require 200 connections. Database capacity must also reserve provider/admin connections. Check pool wait, statement latency and worker contention under load before increasing these values. Worker separation, distributed dependency probes, private ingress, deployment ordering, and recovery procedures will be added by the remaining implementation tasks.

## Shared sessions (opt-in)

Activate the `distributed` Spring profile alongside the database profile. Local container sessions remain the default. Redis sessions use HttpOnly/SameSite=Lax `JSESSIONID`, the configured Secure-cookie policy, and a two-hour idle timeout. No load-balancer affinity is required. Login stores a sanitized principal before Spring Security saves the context; the session never needs a password hash or submitted password. The serializer's version 1 supports only explicit scalar metadata, CSRF tokens, and ordinary-user security contexts. Adding session attributes or changing that format requires compatibility tests and a coordinated namespace/version rollout.

Configure `REDIS_HOST`, `REDIS_PORT`, `REDIS_USERNAME`, `REDIS_PASSWORD`, `REDIS_TLS`, and a deployment-specific `SESSION_NAMESPACE`. Production distributed startup rejects missing Redis credentials or the local namespace. Redis has two-second connection/command deadlines. Redis is a required readiness dependency in this profile; SMTP remains excluded. Redis unavailability or invalid serialized session data returns 503, without a container-session fallback. Expired/missing sessions remain unauthenticated.

For the optional local Compose service, set `SPRING_PROFILES_ACTIVE=prod,distributed` in the shell or `.env` and run `docker compose --profile distributed up --build`. Use a distinct Redis password. Redis has no host port, a 256 MiB default memory budget and `noeviction`; its limits are an example to qualify, not a production sizing promise. Alert on memory approaching the ceiling, write failures, session errors and readiness loss. Session TTLs bound retention. Deleting the session namespace intentionally signs out all accounts; do so only under an approved incident/recovery procedure.

Run real-service checks with `./mvnw -Pdistributed-it -Dit.test=DistributedSessionIT verify` from `backend/`. The test owns a disposable Redis container and starts two HTTP application contexts against an isolated database. If Docker is unavailable, these checks fail visibly. A supplied `-Dcampus.test.redis.port=<disposable-loopback-port>` can qualify routing/restart against an isolated local Redis instance; container-stop outage checks still require an owned container.

## Shared authentication admission

Distributed mode uses an atomic Redis token bucket rather than a per-process bucket. All API instances must use the same `AUTH_ADMISSION_NAMESPACE` and `AUTH_ADMISSION_HMAC_SECRET` (at least 32 characters). Keys contain HMAC identifiers rather than email/IP plaintext. The cardinality index and bucket share a Redis Cluster hash slot. Cleanup visits at most 64 expired entries per admission; active restrictions are never evicted to make room. Bucket TTLs expire only when tokens would be fully replenished. New identities are denied with a positive Retry-After when the configured key ceiling is reached.

Keep Redis's eviction policy `noeviction`: write failures, timeouts, malformed replies and policy mismatch return safe 503; they never become successful admissions or misleading 429 quota denials. Namespace cardinality defaults to 10,000 keys (`AUTH_MAXIMUM_KEYS`), and per-process expensive password work remains capped at two (`AUTH_MAXIMUM_ACTIVE`) across login, registration, resend and confirmation. Qualify these budgets against shared-NAT traffic and login throughput. An admission-store outage can affect existing sessions too when sessions and admission share one Redis service; separate service placement can be introduced if isolation is required.

Coordinate changes to bucket policies across instances. The Lua script fails closed on an incompatible active bucket instead of resetting its restriction during a rolling deployment. Plan namespace/secret rotations deliberately: changing either abandons old quota state. Source: [Redis Lua scripting](https://redis.io/docs/latest/develop/programmability/eval-intro/); the script accesses only its two declared keys.

Verification counters remain committed database fixed windows. `VERIFICATION_GLOBAL_MAIL_DAILY_LIMIT` defaults to 1,000 and is configurable; changing it should track mail-provider capacity and abuse monitoring. Existing per-recipient and peer-IP windows remain enforced.

Local forwarded headers remain ignored (`NONE`). Distributed mode uses Tomcat native handling with no trusted forwarding peers by default. `TRUSTED_PROXY_REGEX` must match the private ingress's actual peer addresses; do not configure every private network or the whole Internet as trusted. Ingress must strip user-supplied forwarded headers and prevent direct API access. Tests cover untrusted headers, trusted client separation, spoofed leftmost hops, and shared-NAT budgets. A first-party gateway's routing configuration and trust range must be reviewed together.

## Dedicated mail workers and key rotation

Use the Dockerfile's `worker` target, which runs `com.campus.tracker.worker.WorkerApplication` with a non-web context. Select `WORKER_ROLE=mail` and the production database/mail profile; supply only the `campus_mail_worker` database credential. Do not start the ordinary web entry point with the worker profile. The worker imports no HTTP controllers, session store, security filters or JPA entities. The export role remains intentionally unavailable until export processing is introduced.

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
