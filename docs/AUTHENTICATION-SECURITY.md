# Authentication abuse controls

The backend applies process-local limits before authentication or account creation. Existing CSRF protection, session authentication, and per-user application ownership checks remain enabled.

## Defaults

Configure these under `campus.auth-abuse` in application.yml or with Spring environment variables.

| Setting | Default |
| --- | --- |
| maximum-keys | 10,000 shared bucket entries |
| maximum-active | 2 simultaneous login/registration operations |
| login-ip.capacity / refill-period | 20 / 1 minute |
| login-email.capacity / refill-period | 10 / 10 minutes (one token/minute) |
| registration-ip.capacity / refill-period | 3 / 1 hour |
| csrf-ip.capacity / refill-period | 60 / 1 minute |

A bucket starts full, consumes one token per attempt, and refills continuously. This is not a fixed-window counter. For example, `CAMPUS_AUTH_ABUSE_MAXIMUM_ACTIVE=4` changes the work cap; measure BCrypt CPU and database capacity before increasing it.

The shared CSRF bucket covers GET/HEAD /api/auth/csrf and **all anonymous unsafe requests**, including unmapped paths and incorrect methods. Spring can allocate a session before rejecting an invalid CSRF request, so limiting only the token endpoint would leave a bypass. Authenticated application writes do not consume this anonymous bucket.

Login additionally consumes peer-IP and canonical-email budgets; registration additionally consumes its peer-IP budget. Email canonicalization matches authentication: trim and lowercase with Locale.ROOT. Missing, duplicate, or oversized email parameters are rejected before password verification. Existing and nonexistent accounts use the same limits.

## Responses and client behavior

A denied request receives HTTP 429, a positive Retry-After value in seconds, and:

```json
{"message":"Too many authentication requests. Please try again shortly."}
```

The frontend preserves the server message and retry interval, including when the CSRF request is throttled. It displays the wait and never automatically resends a write.

The concurrency guard rejects immediately when full, without queuing or sleeping. A permit is released on success or exception. Registration acquires its permit before entering the account transaction.

## Boundaries and operations

- Active restrictions are never evicted to admit new identities. When bucket storage is full, new keys are temporarily rejected; replenished buckets are eligible for reclamation.
- Cleanup work is rate-limited to avoid repeated full scans under a random-identity flood.
- Rejection totals are logged without passwords, tokens, or raw account identities. A production metrics dashboard and work-utilization gauge are not included.
- Forwarded-header interpretation is disabled by default. Limits use the actual peer address, not caller-supplied X-Forwarded-For.
- Configure trusted proxies and block direct backend access before deploying behind a proxy. Otherwise all clients may share the proxy's bucket.
- Counters reset at restart and are not shared across replicas. Add shared atomic storage before horizontal scaling.
- Tune IP budgets for legitimate users behind shared NAT. Short account cooldowns can still inconvenience a targeted user; these controls do not prevent a large distributed denial-of-service attack.
- Public hosting also needs upstream connection/request-size limits, HTTPS, and operational monitoring.

## Verification

Limiter unit tests check refill timing, concurrent admission, bounded storage, and permit cleanup. Security integration tests cover IP/email rotation, case/whitespace normalization, spoofed forwarding headers, registration rejection, CSRF GET/HEAD, anonymous unsafe paths, context paths, saturated work, and normal login/registration.

Run from backend:

```powershell
.\mvnw.cmd -B test
```

Run from the repository root:

```powershell
npm test
npm run build
```

See [VALIDATION.md](../VALIDATION.md) for the actual verification record and [database security](DATABASE-SECURITY.md) for role separation.
