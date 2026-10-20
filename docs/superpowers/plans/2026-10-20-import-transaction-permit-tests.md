# Import Transaction Permit Regression Tests

> **For agentic workers:** Use superpowers:subagent-driven-development. The parent owns integration and validation; one implementer owns the test file.

**Goal:** Prove through real HTTP that import permits stay occupied through actual transaction commit and rollback after confirmation parsing and real writes.

**Architecture:** Keep production beans and transaction advice. Add a test-only collaborator interception which proceeds with real receipt persistence, then registers transaction synchronization. A bounded beforeCommit gate exposes transaction-local writes and holds completion until the test releases it; a controlled exception exercises rollback. Test-only outer request completion observation prevents release-probe races.

**Tech stack:** Java 17-compatible JUnit, Spring Boot/Security, real embedded Tomcat, isolated H2, existing Mockito subclass spy or Spring advisor infrastructure.

**References:** docs/API.md CSV imports; docs/CODE_GUIDE.md CSV boundaries; VALIDATION.md final section; user request pasted on 2026-10-20.

## Constraints and review focus

- Earlier AGENTS.md instructions were explicitly revoked; preserve all existing user changes.
- Do not fake ImportCommitService, manually acquire the tested permit, add production hooks, or alter runtime behavior.
- Observe a real active transaction and already-issued application/receipt SQL, then hold before physical completion.
- Separate body deadlines from the 30-second transaction timeout; keep every gate/wait bounded and clean up in finally.
- Assert IMPORT_BUSY, serialize admission probes, and distinguish owner/process work limits from NOWAIT admission failures.
- Check pending status without claiming completion, exact success/rollback persistence, preview payload/reservation retention, and recovery for both owners.

## Task 1: Investigator (read-only)

- [x] Trace transaction/filter lifetime, proxy-safe hooks, existing helpers and cleanup risks; parent independently inspects and reconciles the recommendation.

## Task 2: Implementer (exclusive test ownership)

**File:** backend/src/test/java/com/campus/tracker/application/importing/ImportCommitPermitServerTest.java (or justified extension of existing transport test).

- [x] Use one configured process slot and real authenticated/CSRF-protected HTTP requests.
- [x] Add successful-commit and forced-rollback cases with a gate inside real transaction synchronization after writes.
- [x] While gated, check same-owner and other-owner IMPORT_BUSY plus live status; after completion check durable outcome and actual HTTP admission recovery.
- [x] Run focused tests, demonstrate an early-permit-release mutation produces the intended assertion failures, restore exact original production bytes, and rerun green.
- [x] Self-review and report exact evidence/commands; no commit or unrelated edits.

## Task 3: Parent integration and parallel independent review

- [x] Verify the patch and evidence independently; dispatch fresh read-only transaction/test-correctness and resource/compatibility reviewers concurrently.
- [x] Wait for both; confirm actionable findings and assign fixes to the test owner, then recheck.
- [x] Run new focused tests, existing ImportConfirmationTransportServerTest and ImportLifecycleTest, full backend tests, and git diff --check.
- [x] Update VALIDATION.md with exact results; remove the coverage-gap note only after success/rollback/mutation checks pass.
- [x] Report files, subagent outcomes, synchronization proof, mutation evidence, check results and remaining limits.
