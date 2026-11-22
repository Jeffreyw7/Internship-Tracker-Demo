# Password Reset release evidence

**Public activation remains on hold.** JEF-14 selected a Render Free service for staging. Public registration and password recovery are disabled there.

## Staging ingress review

On November 21, 2026, staging revision `b607664f507fb094abd0f041b42ca1aa6d55c2aa` passed the HTTPS redirect, TLS 1.3, one-year HSTS, session cookie and API cache checks. HSTS was present on `/` and `/api/auth/csrf` (200) and anonymous `/api/auth/me` (401). The deployment identified Tomcat on port 10000. Render's routing policy excludes direct public access to that listener. [Image build](https://github.com/Jeffreyw7/Internship-Tracker-Demo/actions/runs/44983048409) · [Render port routing](https://render.com/docs/web-services#port-binding)

On November 22, 2026, the project owner waived the six-second public password-reset cutoff for JEF-16. In the earlier hosted check, the client finished sending at 7.21 seconds and received HTTP 400 from application validation at 7.26 seconds. The reset filter still limits body size and read time. CSV preview and confirmation retain their 30-second absolute upload deadline, five-second blocked-read allowance and proxy byte limits. [Reset filter](../backend/src/main/java/com/campus/tracker/auth/reset/ResetInputFilter.java) · [Import contract](API.md#csv-imports)

The November 22 anonymous GET check negotiated TLS 1.3 and observed HSTS on the same 200, 200 and 401 responses. Both API responses carried `Cache-Control: no-store` and `CF-Cache-Status: DYNAMIC`, with no `Age` header. The CSRF session cookie carried `Secure`, `HttpOnly` and `SameSite=Lax`. [Hosted evidence](https://linear.app/jeffreywang/document/jef-16-staging-ingress-evidence-2570b21f0837)

A health GET took 62.18 seconds before returning 200. The probe allows up to 90 seconds per GET to accommodate a Free service starting after idle. Operators can set a shorter client timeout with `--deadline-ms`. [Render Free service spin-up](https://render.com/docs/free#spinning-down-on-idle)

## Executed checks

The local checks below were recorded on October 29, 2026.

| Check | Evidence and scope |
| --- | --- |
| Frontend | 64 Node/API/mounted-DOM tests pass; TypeScript and Vite production build pass. Three new regressions cover the reset-view reload hint, dismissal/completion cleanup and verification-link precedence. |
| Backend | Final complete unit/H2 suite: 129 passed, no failures/errors/skips. All five PostgreSQL 17 scenarios pass across the migration/monitoring run and corrected restore rerun. |
| Deployment probe | The October probe passed five fake-server checks, including deadline failures, capacity rejection and a cutoff before the late byte. Its arguments rejected remote cleartext origins and embedded credentials. |
| PostgreSQL 17 | Fresh, legacy and V3 upgrade scenarios pass on portable PostgreSQL 17.11, including restricted roles, HTTP recovery and primary-clock expiry after row-lock waits. |
| Private monitoring | Actual report executes on PostgreSQL 17 under a role restricted to metadata-column SELECT. Seeded backlog/notice alerts, consumed-link exclusion, missing/known/future export checkpoints and denied secret reads/control writes pass. |
| Restore and restart | A pre-reset logical backup is restored into a separate isolated database. The drill demonstrates resurrected old credentials and an active spent link offline, rejects incorrect reconciliation arguments, reconciles, rejects old credentials/links, then completes fresh mailbox recovery. A clean database restart preserves the completed reset, audit and notice. |
| Real browser | Codex in-app browser displays labeled fields, scrubs fragments, enforces required input and requires explicit sign-out for signed-in visitors. At 375×812 the form fits without horizontal clipping. Reload now shows reopen-email guidance with no secret restored. Dismissal followed by reload stays at sign-in. Login in another tab updates the first tab; cross-tab logout restores the anonymous recovery form. |
| HTTP/SMTP | Rebuilt bundled app passes loopback SMTP reset-link/notice delivery, slow-body 408, single use, credential/session revocation, preserved data/theme and file-H2 JVM restart. Browser fixtures use synthetic verified accounts and expire after 15 minutes or an explicit stop file. |
| Local transport | `/` and CSRF responses pass no-store/referrer checks; session cookie has HttpOnly and SameSite=Lax. Stalled body: about 5.0 seconds; a byte at 4 seconds followed by a stall: about 9.0 seconds, both HTTP 408 within an 11-second measurement budget. HTTP does not establish hosted TLS/HSTS/Secure-cookie behavior. |
| Local capacity | Four admitted known/unknown/suppressed intake samples: maximum 27.4 ms. One completion: 281.2 ms. A 24-request same-IP burst at concurrency eight returns one 202 and 23 throttled 429 responses with Retry-After. Generic responses and log redaction pass. These small H2/loopback samples do not establish production percentiles or SLOs. |

PostgreSQL binaries came from [EDB's official distribution](https://www.enterprisedb.com/download-postgresql-binaries), linked by [PostgreSQL's Windows download page](https://www.postgresql.org/download/windows/). Archive: `postgresql-17.11-5-windows-x64-binaries.zip`; measured SHA-256 `80379b2c04d51c30225532e0ae04509899141e9957ed096fe749d7fd9df8f82f`. Binaries, databases and logs remain ignored test outputs. This is a recorded local digest, not a claim of a vendor-signature check.

## Fixes exposed by verification

- Docker's frontend stage now copies and runs the frontend tests before building. Docker context rules exclude root and nested `.env` variants while retaining `.env.example`.
- A token-free boolean in history state preserves reopen-email guidance after refreshing a scrubbed reset link. Tokens/passwords remain in memory only. Dismissal and completion clear the hint; a new verification link takes precedence.
- PostgreSQL 17 treated `\quit 3` as a successful exit. Reconciliation guards now raise SQL exceptions under `ON_ERROR_STOP`, so missing or incorrect target/confirmation returns failure before mutations.
- The H2 expired-link health fixture now places both creation and expiry in the past while preserving the schema's positive lifetime constraint. It avoids comparing independent H2/JVM clock samples at the current instant.
- The October load probe accepted repeated `no-store` directives. Its companion edge probe checked whether the late byte was actually written before reporting a deadline result.

## Checks requiring the deployment

| Gate | Missing evidence needed before activation |
| --- | --- |
| Docker/Compose rollout | Execute image build, startup, migration-failure gating, runtime identity and persistent-volume restart on a Docker host. Docker is absent from this environment. Existing Compose is explicitly local development configuration. |
| HTTPS and trusted edge | The staging review records the HTTPS and listener results. JEF-16 still requires evidence of forwarding-header handling and the proxy's enforcement of import bytes and upload time. [Staging ingress review](#staging-ingress-review) |
| SMTP/provider | Authorized test mailbox and configured provider/sender domain: SPF/DKIM/DMARC, link tracking disabled, actual inbox delivery, bounce handling, supported clients/scanners and recovery/support destinations. Loopback acceptance proves no external delivery. |
| Sustained SLO/load | Section 12 of the design requires at least 30 minutes with managed PostgreSQL, representative workspace/login traffic, distributed legitimate IP/address budgets, boundary bursts and production-equivalent resources. Run the separate campus/shared-NAT and daily mail-ceiling cases. |
| Monitoring and audit export | Connect private report/logs and application/edge latency, 5xx, permits and timeout telemetry to dashboards/on-call alerts; test alert delivery and recovery. Supply a durable audit sink/checkpoint and prove late-commit-safe export, deduplication and retention. The SQL report is not an exporter. |
| Managed durability/failover | Verify the deployed primary's acknowledged-commit durability, failover fencing and backup/session-store procedures. Local logical restore and clean restart do not establish crash or managed failover behavior. |
| Operational sign-off | Real on-call/provider/database owners, maintained password blocklist, support ownership-proof process, secret rotation evidence and engineering/operator approval. |

Keep recovery disabled until these gates have recorded evidence. The scripts do not enable hosted recovery or apply reconciliation automatically.

## Reproduce

From the repository root:

```powershell
npm.cmd test
npm.cmd run build
node --test deploy/probe-edge.test.mjs
node tests/reset-readiness-load.mjs
npm.cmd run test:smoke
# Optional interactive fixture; stop by creating backend/target/browser-readiness.stop.
node tests/http_smoke.mjs --hold-browser
# Anonymous HTTPS, cookie and cache checks on staging.
node deploy/probe-edge.mjs --origin https://<staging-host>
```

From `backend/`, use Maven with `-Pwith-ui,postgres-it -Dpostgres.test.bin=<portable-PG17-bin> verify`. This session used cached Maven 3.9.11 with explicit local repository and user-home arguments. Integration tests always initialize disposable clusters; there is no parameter for an existing operator database.

Private monitoring report:

```sh
psql -X -v ON_ERROR_STOP=1 -f deploy/monitoring/password-reset-health.sql
```

Use a dedicated monitoring identity with only the metadata-column grants exercised in `PostgresMonitoringIT`. A missing checkpoint reports `audit_checkpoint_missing=1` and null lag. Set `audit_exported_through` only after a durable sink proves complete coverage through that timestamp, including ties and late commits; merely scanning currently visible rows is insufficient.

Offline reconciliation, **only after traffic/workers are isolated and every session store is cleared**:

```sh
psql -X -v ON_ERROR_STOP=1 -d <isolated-restored-db> \
  -v restore_database=<isolated-restored-db> \
  -v restore_confirmation=ISOLATED_RESTORE_OFFLINE \
  -f deploy/postgres/reconcile-restored-auth.sql
```

This intentionally blocks **all restored passwords**, revokes both challenge families and erases queued payloads. It preserves audit/counters/application data and leaves recovery disabled. Arrange controlled fresh recovery and validate infrastructure before reopening access. External session stores require separate clearing; advancing an epoch restored from an older backup cannot independently invalidate every post-backup principal.
