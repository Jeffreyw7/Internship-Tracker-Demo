# Browser verification journeys

Run the [verification skill](../SKILL.md) from the repository root. The stock command is `node tests/browser_validation.mjs`. Each feature file names the `results.json` entries that support its coverage.

| Journey | Evidence to inspect |
| --- | --- |
| [Add an application using the drawer](add-application.md) | A new record, its stored note, and a second session reading the record |
| [Review and recover a CSV import](csv-import.md) | Preview status, one server commit, recovery reads, and visible Add and Update effects |
| [Recover a password](password-recovery.md) | Captured local mail, session revocation, and sign-in to the existing account |

## Trial record

At `5d22a8e`, Chrome 158.0.8127.37 passed all 22 runner checks. The live anonymous account request returned `401`. The CSV case recorded one commit (`200`) and one status read. Trial outcomes appear beside each entry point. **Not run** identifies an alternate control or session state the stock runner does not drive. A narrower screenshot or a successful HTTP request alone cannot establish those paths.

Trial evidence is stored locally in `test-results/verify-campus-tracker/1904e28da02f/`. The trial’s `doctor.json` records the anonymous account response and `cleanup.json` records the JVM and listener shutdown and the surviving evidence. For another run, use its printed output directory. Recheck selectors, runner assertions, and these mappings when the UI changes with `$simplicit:maintain-verification-skill`.
