# Disposable load measurements

For the hosted sustained/burst/soak workload, successful authentication, preference polling and explicit acceptance gates, use the [hosted-capacity tooling](hosted/README.md). Its local Docker rehearsal verifies the tooling; hosted infrastructure and capacity remain unqualified.

`npm run test:load` builds a new private Docker project, seeds eight synthetic verified accounts with seven 50-record histories and one 5,003-record history (three stages each), then measures a 20/s reference on the changed stack, 50/s ramp, mail/CSV contention, 200/s burst, a one-minute 50/s soak with an extra replacement API, and invalid-password admission at 5/s. It gathers private API heap/GC/pool metrics, Redis memory, queue counts/age and database connection counts. Only this newly generated project's volumes are removed. No existing database is used. The reference is not a measurement of the original application and establishes no before/after speedup.

The runner schedules arrivals by elapsed time. A full client concurrency budget or late scheduler records a dropped arrival instead of waiting for capacity and reducing the offered rate. JSON reports include scheduled/started/completed/dropped, achieved arrivals, bounded reservoir percentiles and per-route status counts. Requests include list, summary, activity, detail and versioned updates; contention adds actual export creation/polling and SMTP work. Never compare only completed-request latency while ignoring drops, 429, 503/transport or stale-version 409. A report is evidence for the recorded dataset/resources, not proof of 300,000-account capacity.

For a separately controlled experiment, run `node tests/load/seed.mjs` in one terminal; it holds a disposable stack until Ctrl+C and prints the loopback URL/private fixture path. Run in another terminal:

```sh
node tests/load/run.mjs --base http://127.0.0.1:<printed-port> --fixture <printed-private-file> --rate 50 --duration 120 --concurrency 32 --scenario foreground --out backend/target/foreground.json
node tests/load/report.mjs backend/target/foreground.json backend/target/foreground.md
```

Specify the exact fixture origin. Synthetic fixture files hold test passwords and remain in ignored `backend/target`; never commit or upload them. Reports include no account identifiers, cookies, passwords, tokens, notes, payloads or query strings. `auth` uses incorrect synthetic passwords to measure BCrypt and admission without rotating foreground sessions. Legitimate successful signup/login and campus NAT behavior are separately qualified by distributed security tests. A reused HTTP session fixture may reach the configured login/CSRF budget; do not disable production protections to make a benchmark appear successful.

Defaults are deliberately modest for a development computer. Hosted qualification still needs approved resources and a representative population, 60 million applications / 180 million stages, 1,500/s sustained and 2,000/s burst, successful-auth amplification, slow providers, legitimate shared NAT, multi-hour soak and managed failover/restore. Establish an immutable baseline under the same workload/resources before claiming a measured improvement. Avoid an unbounded client queue, all-history preload, fabricated latency samples, secret-bearing URLs and measurements against an existing user database.
