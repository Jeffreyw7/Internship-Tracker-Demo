# Local internship tracker demo

This Windows walkthrough shows the Java and React edition of Campus Internship Tracker with a synthetic account, a disposable H2 database, and a local Mailpit inbox.

## Preflight

Use a clean checkout of `main`, Node.js 22.18 or later, and Java 17 or later. The first install may download npm dependencies, Maven dependencies, and the pinned Mailpit binary. Build and start the app before the demo. Run these commands from the repository root in PowerShell.

```powershell
npm.cmd ci --include=dev
npm.cmd run build
Push-Location backend
.\mvnw.cmd -Pwith-ui package
Pop-Location
powershell -NoProfile -ExecutionPolicy Bypass -File .\start-local.ps1 -DataDirectory .\test-results\demo-data
```

If the Windows Maven wrapper stops before Maven starts, run an installed Maven 3.9.11 `mvn.cmd -Pwith-ui package` from `backend/`, as described in the [CI guide](CI.md#reproduce-application-verification). Check that `http://127.0.0.1:8080/api/health` reports `UP` and that the inbox opens at `http://127.0.0.1:8025`. Use a fresh directory such as `test-results/demo-data-2` for an empty rehearsal. For the restart step, reuse the directory you started with. [Local launcher guide](LOCAL-PASSWORD-RESET.md)

Save this UTF-8 fixture as `demo-applications.csv`. Keep it in the demo folder so it is easy to find during the walkthrough.

```csv
Company,Role,Location,Notes
Harbor Labs Demo,Data Intern,Remote,Synthetic CSV example
```

## Walkthrough

1. **Verify an account.** Open the app, choose **Create an account**, and use `Demo Student` and `student@example.test` with a unique demo password. Clear **Start with 10 sample applications** and choose **Create workspace**. Open the verification message in Mailpit, follow its link, confirm with the signup password, and sign in. Keep the verification link and password off screen when capturing evidence.
2. **Track an application.** Choose **Add application**. Enter `Northstar Studio Demo` for Company and `Software Intern` for Role. Choose **Interviewing** and set the application and follow-up dates to today. Add a **Scheduled** stage called `Technical screen` for today and save. Open **Interviews** and **Follow-ups** to show both activities. Mark the follow-up done and use **Undo** to restore it.
3. **Import and export.** Return to **Applications** and choose **Import CSV**. Pick the fixture and select **Read file and show columns**. Map the four columns to **Company**, **Role**, **Location**, and **Notes**. Choose **Create revised preview**, check that it shows one add and zero updates, then choose **Review final confirmation** and submit the import. Close the receipt and choose **Export CSV**. Confirm that the downloaded CSV contains both demo companies. Use a screenshot of the signed-in Applications workspace to show the synthetic data.
4. **Show persistence.** Set **Appearance** to **Dark**. Stop the local launcher with Ctrl+C, then run the same launch command with the same data directory. When health reports `UP`, reload the app and sign in again. Both rows, the saved interview stage and the Dark preference should still be present.

## If the demo is interrupted

- If a loopback port is in use, rerun with three available ports, for example `-Port 8081 -InboxPort 8026 -SmtpPort 1026`. Use the URLs printed by the launcher.
- If the email has not arrived, check the local inbox and the address you entered. Use the app’s resend control if needed, and open the latest message.
- If a CSV confirmation is interrupted, use **Check status** and read the receipt before deciding whether to retry the reviewed import. If a receipt is already visible and the application list is stale, use **Retry refresh**.
- If an application is absent after restart, check the data directory printed by the launcher and confirm you used the same account. Preserve the original directory to recover the rehearsal.

This demo uses loopback HTTP and local mail capture. Follow-ups appear in the workspace and send no reminders or calendar events. The browser runner can rehearse the core workflows separately with disposable data and capture a screenshot and per-check results. [Browser validation](BROWSER-VALIDATION.md)

