# Local replicated Docker environment

Docker is the selected environment for the current development/testing phase. AWS provisioning and hosted capacity/recovery qualification are deferred. This setup runs two APIs, PostgreSQL, Redis, private export storage, mail/export workers, a static gateway and a local verification inbox on one computer.

Docker Desktop must use Linux containers. Run these commands from the repository root with the prepared private `.env` and storage identity file:

```sh
docker compose -p campus-local-scalable -f compose.scalable.yaml -f compose.local.yaml up -d --build
docker compose -p campus-local-scalable -f compose.scalable.yaml -f compose.local.yaml ps
```

Open [the application](http://127.0.0.1:8080) and [the verification inbox](http://127.0.0.1:8025). Create your account in the app, open its verification email in the inbox, follow the link, and enter your signup password to confirm. No account is created automatically.

Only the gateway and local capture inbox bind host ports, both on loopback. API, PostgreSQL, Redis and storage ports remain private. HTTP/non-secure cookies and capture mail are local settings. The storage identity file supplies private read-only API and bucket-scoped worker credentials; `.env` and `*.private.json` are ignored by Git. Keep them stable across restarts so the persistent data and queued mail remain usable. Never share their contents or the resolved Compose configuration output.

Stop containers while keeping data:

```sh
docker compose -p campus-local-scalable -f compose.scalable.yaml -f compose.local.yaml stop
```

Run the startup command again to resume. Named database, Redis and storage volumes retain data. Do not use `down -v` to stop this environment: that removes its saved data. This PostgreSQL dataset is separate from the native `backend/data` H2 database; existing native records are not imported automatically.

For isolated verification, run `npm run test:distributed`, `npm run test:hosted-capacity:smoke` or the modest `npm run test:load`. Those harnesses create/remove only their own UUID projects. They do not load-test or delete this persistent environment. Run Docker fixtures sequentially. This single-host environment exercises distributed application behavior; multi-zone recovery and full hosted capacity remain unqualified.
