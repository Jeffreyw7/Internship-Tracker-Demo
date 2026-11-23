# Add an application using the drawer

Start signed in to a ready workspace. The runner creates a verified synthetic account with sample data unchecked. Success means the new application is visible after submission and its saved details can be read from another session.

| Entry point | Drive and observe | Trial |
| --- | --- | --- |
| Welcome section → **Add application** | Fill **Company** with `Acme Synthetic` and **Role** with `Browser intern`. Select **Interviewing**, use today’s application and follow-up dates, add the note and a scheduled **Technical screen** stage, then submit **Add application** inside the dialog. Open the company button, read the note, and open the record independently after signing in again. | Passed on Applications |
| Welcome button via keyboard | Focus **Add application**, press Enter, tab inside the dialog, then Escape. Focus returns to the opener. | Passed for opening and cancellation |
| Empty Applications table → **Add application** | With zero records, choose the button below **No applications found**. Submit the same fields, reload the workspace, and reopen the company to check the saved role and note. With existing records and an empty filtered result this control becomes **Clear filters**. | Not run through this opener |

The welcome button is also present on Interviews and Follow-ups. Submission from those views is unverified. At 390 and 320 CSS pixels the runner opens and closes the drawer. Full mobile submission is unverified. Creating records in a batch is covered by the [CSV journey](csv-import.md).

Look for `Application create and detail read`, `Application shortcut, modal entry, Escape and focus return`, and `Two independent sessions produce a real HTTP 409; remote change survives` in `results.json`. The last case opens the existing record in the second session before editing. The disposable H2 database owns the created record. [Workspace controls](../../../../src/App.tsx) · [Drawer](../../../../src/components/ApplicationDrawer.tsx)
