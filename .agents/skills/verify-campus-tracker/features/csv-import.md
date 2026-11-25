# Review and recover a CSV import

Start in the signed-in workspace with one existing synthetic application. The runner clicks **Export CSV** and takes its real Application ID from the browser download. Its file contains one update with that ID and one new `Orion CSV` row with a missing role.

| Entry point or state | Drive and observe | Trial |
| --- | --- | --- |
| Workspace panel → **Import CSV** | Choose the CSV file, click **Read file and show columns**, map Application ID, Company, Role and Notes, and create a revised preview. **Go to next row with errors** focuses the invalid Role. Enter `CSV intern` and revise again. Expect HTTP `201` and one Add and one Update. | Passed on Applications |
| **Review final confirmation** | The submit button enables after acknowledging the existing update. The runner forwards one commit, then drops its response inside the local browser. Close the dialog at **Completion not yet confirmed** and observe the disabled Add button. | Passed for the lost-response case |
| Pending banner → **Check import** followed by its reference | Reopen the dialog, click **Check status**, and read **Import completed**. Inspect the new Orion row and the changed note on the existing record. Expect one commit, server status `200`, and a recovery GET. | Passed |
| Pending import via the toolbar, or restored after a page reload | Use **Import CSV** or reopen the pending reference after reloading the same tab. Check the retained reference and read status before choosing any further write. | Not run through these routes |
| Completed or retired review | From a receipt, choose **Start another import**. From a retired review, choose **Start a fresh review**, with the duplicate-risk acknowledgement if shown. Expect the file selection step. | Not run |

The same toolbar appears on Interviews and Follow-ups. Opening from those views, a clean confirmation response, **Recover preview status**, explicitly retrying a reviewed import, and **Retry refresh** after a failed history read remain unverified in this browser trial. The commit changes the disposable account’s data. Replaying it would invalidate the single-commit check.

Inspect `Browser CSV correction, review, update acknowledgement and lost-commit recovery` and `CSV validation can focus the described invalid Role field` in `results.json`. Use `detail.commits`, `detail.serverCommit`, and `detail.statusReads` for the recovery result. [Import dialog](../../../../src/components/ApplicationImportDialog.tsx) · [Browser runner](../../../../tests/browser_validation.mjs)
