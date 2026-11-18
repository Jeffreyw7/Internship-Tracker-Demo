# Browser validation

The [JEF-12 runner](../tests/browser_validation.mjs) drives installed Google Chrome against the bundled Spring Boot app. It uses synthetic accounts, a disposable H2 database, and a loopback SMTP capture listener. It enables password recovery only in that disposable database.

## Reproduce

From the repository root on Windows, build the UI and bundle it into the application JAR. Use the currently checked out source for both builds.

```powershell
npm.cmd ci --include=dev
npm.cmd run build
Push-Location backend
.\mvnw.cmd -Pwith-ui clean package
Pop-Location
npm.cmd install --prefix test-results/browser-tools --save-exact --no-audit --no-fund playwright-core@1.65.1 axe-core@4.14.1
node tests/browser_validation.mjs
```

If the wrapper cannot launch on Windows, invoke Maven 3.9.11 directly with the same profile and goals from `backend/`. Set `BROWSER_VALIDATION_CHROME` to a Chrome executable if Playwright cannot find the installed browser.

The runner writes `results.json`, `server.log` and a 320 px workspace screenshot under `test-results/browser-validation/`. The JSON records the Git revision, working-tree state, bundled JAR hash, Chrome version, per-check outcomes, and axe findings. Set `BROWSER_VALIDATION_OUTPUT` to retain a second run in a separate directory. The process exits nonzero when an assertion fails.

## Coverage

| Scenario | What the runner verifies |
| --- | --- |
| Verification and sessions | Register two accounts, reject sign-in before verification, open SMTP-captured links, sign in and out, and observe a shared browser session switch in another tab. |
| Applications and activities | Create and read an application with an interview and follow-up, update it in a second independent session, complete activities, and delete and undo. |
| Conflict | Force a stale save through two real browser sessions. Verify HTTP 409, focus on the error, the retained draft, and the remote value after refresh. |
| CSV | Export an ID, map columns for an Add and an Update, focus and correct the invalid Role, create a revised preview, and acknowledge the Update. Forward one commit to the server, discard its response, and verify the write lock, GET-only recovery, receipt, and resulting records. |
| Password reset | Request and open the captured link, verify mismatch feedback, reset the password, and check that the prior independent session and password fail while the new password opens the existing workspace. |
| Appearance and accessibility | Verify light, dark, and system behavior, preference persistence and isolation, keyboard navigation, drawer focus and Escape, focus after deletion, filter state, form labels and errors, and a responsive workspace, drawer and navigation at 390 and 320 CSS px. |

Axe runs WCAG 2.0 through 2.2 A and AA rule tags for desktop sign-in and the applications workspace in both themes. The runner waits for theme transitions to finish. It also measures text against gradient endpoints and checks the application count badge using computed colors. Axe reports those elements for manual review. The sign-in logotype is exempt from the minimum text contrast requirement. The browser evidence covers the named scenarios in the tested Chrome build. Screen readers, other engines, external SMTP, hosted deployment, and full WCAG conformance require additional evidence. [WCAG contrast guidance](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html)
