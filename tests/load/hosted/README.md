# Hosted HTTP capacity qualification

This implements the workload and acceptance tooling for Task 12. It prepares no cloud resources and executes no hosted traffic automatically. Provider/account, region, budget, disposable test origin, secrets references, approved targets and recovery policies remain required before platform IaC and hosted execution. The example targets are planning assumptions, not an approved production commitment.

Use the pinned [k6 1.6.1 release](https://github.com/grafana/k6/releases/tag/v1.6.1). The local rehearsal uses `grafana/k6:1.6.1@sha256:a5ad6bc089a08d77c3ec49f3db8c6fa7a148e4073efcac44c675dbaf3568d8e1`. No remote JS imports or paid k6 service is required. Run full qualification on dedicated Linux load infrastructure, separate from the measured application resources. The existing Node load client remains useful for modest local experiments.

## Workload and proposed gates

| Phase | Foreground iterations/s | Preference GETs/s | Password operations/s | Planned HTTP/s | Duration |
| --- | ---: | ---: | ---: | ---: | --- |
| Reference | 70 | 40 | 10 | 150 | 5 minutes |
| Sustained | 700 | 400 | 100 | 1,500 | 30 minutes |
| Burst | 1,000 | 600 | 100 | 2,000 | 5 minutes |
| Soak | 700 | 400 | 100 | 1,500 | 4 hours |

Foreground VUs issue a repeating 50% list, 20% summary, 10% activity, 10% detail and 10% versioned update mix. Each VU exclusively owns its synthetic mutation record and updates only editable fields. Writes are never retried. VUs bootstrap their detail once; these extra reads have a separate counter and remain visible in status totals. Preference polling uses an independent arrival stream. Successful password operations perform four requests: CSRF, login, refreshed CSRF, logout. Cookie jars are isolated and cleared between authentication workflows; passwords, tokens and payloads are excluded from reports. Signup/verification and worker/provider contention remain separate qualifications.

[Constant-arrival-rate executors](https://grafana.com/docs/k6/latest/using-k6/scenarios/executors/constant-arrival-rate/) pace iterations independently of response time. Fixed preallocated VU ceilings prevent an unbounded client queue. A missed arrival, incomplete HTTP/authentication count or unexpected status fails the workload. The report preserves route/status counters, including 429, 503, 409 and transport status 0. Every foreground/polling route needs actual latency samples and request coverage. Proposed gates are API p95 <300 ms, p99 <1 second and password HTTP p95 <2 seconds, with zero unexpected outcomes in this controlled capacity run. That zero-error assertion does not redefine the separate monthly 99.9% availability target. Latencies include client blocking, connection and response consumption.

## Prepare an isolated target and private sessions

1. Select the provider configuration and provision a **new disposable** environment through the approved deployment workflow. Use HTTPS, secure cookies, private managed services, migration gating, private storage and restricted worker roles. Reserve rollout connection overlap. Record the promoted image digest, exact instance/service/autoscaling limits, load-generator resources and estimated monthly cost in a protected resource inventory. Never use an existing customer database.
2. Seed only synthetic `load-<number>@example.test` accounts. The full population target is 300,000 accounts, 60 million applications and 180 million stages, including soft-deleted history and a representative mix of small and 10,000+ record accounts. The eight-account Docker seeder does **not** create that population. A provider-specific, resumable bulk-seeding job still needs the approved infrastructure/storage budget. Never register 300,000 accounts through the public signup/SMTP path to obtain a dataset.
3. Run `inventory.sql` through a protected `psql` connection (`-X -v ON_ERROR_STOP=1 -At -f tests/load/hosted/inventory.sql`). It refuses non-synthetic accounts, uses a read-only transaction and emits exact counts/skew/time without account identifiers. Capture its output before timed load; count scans at this scale can be expensive. Keep the inventory evidence with the resource record.
4. Copy `target.example.json` into ignored `backend/target/`, assign a fresh run UUID and exact origin, replace example image/resource/cost values and inventory counts/time, and set the current account-local calendar date. Set `targetsApproved` only after agreement on the proposed targets. Choose VU budgets from measured duration and load-generator resources. Order fixture slots so early/active VUs include both small and large histories; declaring a large database alone does not exercise its skew.
5. Prepare a private identity file with this shape. Each session slot needs a different synthetic account and a different application owned by that account. Supply at least the sum of foreground, preference and auth VU ceilings for the largest phase, up to 5,000 slots. Supply a separate rotating `authIdentities` pool, disjoint from session accounts: at least 60 identities per offered password operation/second (6,000 for 100/s), up to 100,000. Rotation uses the scenario-wide iteration index; each identity is used at most once a minute under the default quota policy. Account passwords come from protected synthetic fixture storage, never the command line:

```json
{
  "kind": "campus-hosted-identities-v1",
  "runId": "<same-run-UUID>",
  "base": "https://<exact-disposable-test-origin>",
  "authIdentities": [
    {"email": "load-6001@example.test", "password": "<synthetic-secret>"}
  ],
  "accounts": [
    {"email": "load-1@example.test", "password": "<synthetic-secret>", "applicationId": "<owned-record-UUID>"}
  ]
}
```

```sh
node tests/load/hosted/prepare.mjs backend/target/capacity-plan.json backend/target/identities.private.json backend/target/sessions.private.json
```

Preparation validates the complete target and all slots before networking. It logs in, captures rotated CSRF/session state and verifies application ownership through a read; it creates no applications or accounts. Logins are paced 3.5 seconds apart to respect the default IP budget, and a rejected request stops preparation without retries or writing output. Large preparations can take hours. Prepare/shard credentials through protected load infrastructure and qualify two-hour idle expiry; keep all slots needed for later phases active or prepare fresh sessions for that phase. Session files are secrets: `*.private.json` is ignored, Unix permissions are 0600, and Windows files need the protected workspace ACL. Delete private fixtures after the run. Never upload them as CI artifacts.

## Execute and evaluate

With a native pinned k6 executable, set these variables in the dedicated load-generator environment. Repeat the run for `reference`, `sustained`, `burst` and `soak`, changing phase and output each time:

```sh
export CAMPUS_CAPACITY_PLAN="$PWD/backend/target/capacity-plan.json"
export CAMPUS_CAPACITY_FIXTURE="$PWD/backend/target/sessions.private.json"
export CAMPUS_CAPACITY_PHASE=sustained
export CAMPUS_CAPACITY_OUT="$PWD/backend/target/sustained.json"
k6 run tests/load/hosted/capacity.k6.js
```

Use `$env:NAME='value'` in PowerShell. TLS verification stays enabled; redirects and failed-write replay are disabled. Do not enable HTTP debug output or export raw URL/body/session metrics. The custom summary allowlists counters and numeric timings, removing origin, identity and arbitrary metric/config extensions. A nonzero k6 exit or a failed workload check is a failed run.

```sh
npm run capacity:accept -- backend/target/sustained.json backend/target/burst.json backend/target/soak.json
```

Acceptance recomputes gates from measured values, requires all three phase durations/rates, successful password and polling workload, the same run/dataset/deployment resource inventory, approved targets and representative counts. It returns a failing exit for rehearsals, undersized fixtures, missing phases or failed measurements. The report's achieved HTTP rate uses k6's measured counter rate, including the final drain; four-request authentication workflows are sequential, so instantaneous HTTP arrivals differ from the planned average. Complete workflow and request counts, dropped iterations and latency/status gates remain mandatory. The verdict is **HTTP capacity conditional on the supplied inventory evidence**; it does not verify the integrity of an edited report or replace inspection of protected infrastructure/metrics evidence.

Capture private Prometheus pool acquisition/wait/active/pending, connection ceilings during replacement overlap, API/worker heap/GC/CPU, Redis memory/eviction/write errors, database CPU/IO/query plans/locks, queue age and mail/export results throughout the full run. The k6 report alone does not collect these server metrics. Qualify SMTP/storage degradation, real CSV and mail contention, rollback and failover/PITR using [recovery procedures](../../../deploy/RECOVERY.md), and record browser first-useful latency separately. An immutable same-workload/resource original-version reference is still needed before claiming a speedup.

### Authentication admission and load sources

The default shared-IP login bucket allows 20 initial attempts and refills 20/minute; per-email admission allows 10 attempts and refills 10/10 minutes. Expensive password work is bounded per API. A single generator IP or repeatedly authenticating one identity at 100/s will correctly receive 429, even if the rest of the service has spare capacity. Preallocating more VUs does not bypass these policies. Successful-auth qualification therefore requires an approved legitimate traffic distribution, enough independent identities, real routable source addresses, and measured API password-work capacity. k6's [local IP option](https://grafana.com/docs/k6/latest/using-k6/k6-options/reference/#local-ips) can bind owned source addresses; NAT may still collapse them to one peer. Do not spoof forwarding headers, silently disable quotas or raise limits solely to pass a benchmark.

This first harness uses one k6 process and evaluates its complete offered load. It does **not** merge independently segmented/sharded reports or average their percentiles. Choose supported source-address/load infrastructure after hosting inputs; distributed orchestration and histogram aggregation require explicit implementation/validation if needed. Shared-campus-NAT policy remains a separate business/security decision, and rejected legitimate authentication is a failed capacity result until resolved.

## Local tooling rehearsal

```sh
npm run test:hosted-capacity:smoke
```

Docker must be running. This creates a new UUID-owned stack, observes actual PostgreSQL counts, prepares synthetic sessions, and runs the immutable k6 image through the private ingress. It covers successful CSRF/login/logout, preference polling, real versioned updates, route latency/status counters and verdict consistency. It removes only its test containers/volumes and private session fixtures. A six-second, 16 planned HTTP/s rehearsal validates the tooling, not the hosted targets. Shared development/CI hardware can miss arrivals or fail latency gates: the strict capacity verdict and k6 failure code stay recorded, while the smoke command succeeds only if the functional flows and verdict/failure-code checks work. CI runs this sequentially after the other Docker fixtures and uploads only allowlisted inventory/summary evidence. Hosted execution still requires all workload gates to pass.
