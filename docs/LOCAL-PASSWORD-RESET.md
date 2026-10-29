# Free local Password Reset

Run the bundled Java/React app with its embedded file-backed H2 database and Mailpit capture inbox. No cloud account, Docker, paid database or email provider is needed. All listeners bind to `127.0.0.1`; emails stay in the local inbox.

## Windows

Java 17+ is required. From this project folder:

```powershell
powershell -ExecutionPolicy Bypass -File .\start-local.ps1
```

The current workspace already has the bundled JAR. On a fresh checkout, build it first with Node 22.18+ and Java 17+:

```powershell
npm.cmd ci
npm.cmd run build
cd backend
.\mvnw.cmd -Pwith-ui package
cd ..
.\start-local.ps1
```

The first launch downloads the pinned official Mailpit Windows x64 binary into ignored build output and verifies its published archive checksum. Subsequent launches use the local copy. The app runs with the local profile and enables password reset only for this dedicated local database.

- App: **http://127.0.0.1:8080**
- Local inbox: **http://127.0.0.1:8025**
- SMTP capture: `127.0.0.1:1025`
- Persistent accounts/applications and messages: `backend/data/local-password-reset/`

Create an account with an address such as `student@example.test` and your own password. Open the inbox, follow the verification link, and sign in. To test recovery, sign out, select **Forgot password?**, enter the same address, open the reset email in Mailpit and follow its link. Verify that the old password fails and the new password signs in.

Press Ctrl+C in the launcher terminal to stop both processes. Saved data remains for the next launch. The launcher uses a separate database from the existing `backend/data/campus` application database.

If a port is occupied:

```powershell
.\start-local.ps1 -Port 8081 -InboxPort 8026 -SmtpPort 1026
```

These are local development settings with synthetic encryption keys and HTTP cookies. Hosted HTTPS, external inbox delivery and managed failover remain deployment checks. Keep the demo and inbox on localhost.

## Repeat the automated local check

With the app and Mailpit running on their default ports:

```powershell
node tests/local-mailpit-smoke.mjs
```

This creates a uniquely named synthetic account and application, reads only its captured messages, and checks verification, neutral recovery responses, rejected CSRF/short-password submissions, successful reset, old-session revocation, single-use links, old/new password login, preserved application/theme and the password-change notice. It leaves its identifiable test data/messages for inspection and keeps both services running. Repeated runs are subject to the app's normal IP/account limits; a 429 requires waiting for that limit to replenish. It does not clear the inbox or use your personal account. Browser rendering and link scrubbing are separate UI checks.

Mailpit reference: [installation](https://mailpit.axllent.org/docs/install/) and [runtime options](https://mailpit.axllent.org/docs/configuration/runtime-options/).
