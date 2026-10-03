# Job Posting and Contact Details Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Preserve posting descriptions, recruiter/referral information, and compensation in the existing application tracker.

**Architecture:** Extend the application aggregate with current posting metadata and keep immutable description revisions in a child table. Existing application saves coordinate both in one transaction; authenticated description reads load large text only on demand.

**Tech Stack:** Java 17, Spring Boot/JPA/Flyway, H2/PostgreSQL, React 19 and TypeScript.

**Spec:** `docs/superpowers/specs/2026-10-03-job-posting-contact-details-design.md`

## Global Constraints

- Work on the user-selected `design/job-posting-contact-details` branch in this checkout.
- Preserve owner-scoped reads, CSRF, `If-Match`, optimistic conflicts, soft deletion, and undo.
- Omitted posting objects or descriptions preserve existing values; explicit null is rejected.
- Snapshot text is plain text, bounded to 100,000 UTF-16 code units, and excluded from application lists.
- Compensation uses decimal strings at the client boundary and `NUMERIC(14,2)`/`BigDecimal` in persistence.
- Do not alter existing migrations or fetch posting URLs.

## Review Focus

- Full-record follow-up/stage actions must not erase metadata or create snapshots.
- Failed description reads must not initialize a blank replacement description.
- Two editors must not overwrite a newer posting or snapshot.
- Clearing descriptions must retain history and explain that retention.
- Archive export must fail rather than silently download incomplete or mixed-version data.

## Task 1: Backend persistence and secured API

**Files:** Add `PostingDetails.java`, `PostingDetailsInput.java`, `PostingDescriptionSnapshot.java`, `PostingDescriptionRepository.java`, `PostingDescriptionService.java`, `PostingDescriptionController.java`, and `V4__job_posting_details.sql`. Modify `InternshipApplication.java`, `ApplicationDtos.java`, and `ApplicationService.java`. Test in `PostingDetailsIntegrationTest.java`.

**Interfaces:** Optional `postingDetails` input uses presence-aware JSON, full scalar metadata, and optional `descriptionSnapshot`. Application responses include `postingDetails.descriptionRevision`. Read APIs return current `{applicationVersion,snapshot}`, paginated history metadata, and a full snapshot.

- [x] Write and run integration tests for capture/history, omission/null, validation, ownership, stale writes, deletion/undo, and absence of text in lists; expect failure before implementation.
- [x] Implement migration, metadata parsing/validation, transactional revision capture, and secured reads.
- [x] Run the Java suite; expect zero failures. Commit backend changes and record evidence.

## Task 2: Client domain, transport, and export

**Files:** Add `src/domain/postingDetails.ts`, `src/data/postingApi.ts`, `src/domain/applicationExport.ts`, and `tests/posting.test.mjs`. Modify `src/domain/application.ts`, `src/data/applicationApi.ts`, and `src/App.tsx`.

**Interfaces:** `PostingDetails`, `PostingDraft`, `blankPostingDetails()`, `postingPayload()`, `validatePostingDraft()`, `postingApi.current/history/snapshot/archive`, and `applicationCsv(apps)`.

- [x] Write and run tests for safe payload mapping, untouched descriptions, exact decimals, paged archives, version changes, partial-read failures, and CSV escaping; expect failure before implementation.
- [x] Add types and explicit mapping, existing transport reuse, bounded export retries, and CSV metadata columns.
- [x] Run `npm test` and `npm run build`; expect zero failures. Commit client model/API changes and record evidence.

## Task 3: Editor and saved details

**Files:** Add `PostingFields.tsx`, `PostingDetailsPanel.tsx`, and a focused description-loading hook. Modify `ApplicationDrawer.tsx` and styles.

**Interfaces:** The drawer remains the owner of the draft; posting components accept typed values/callbacks. Description reads expose loading/error/retry/current state and do not overwrite an active draft. History shows capture/source metadata and supports individual JSON archive download.

- [x] Add meaningful component-rendering checks for plain-text display, empty-capture messaging, complete posting fields, and error/retry behavior.
- [x] Implement safe description loading, explicit editing initialization, archive/history controls, and retention copy.
- [x] Run the client suite and build; verify the complete drawer in the browser with synthetic data. Commit UI changes and record evidence.

## Task 4: Verification and documentation

**Files:** Extend HTTP smoke coverage for restart persistence, migration and secured posting reads; update `docs/API.md`, `README.md`, and `VALIDATION.md`.

- [x] Run `npm test`, `npm run build`, and the Java suite, plus HTTP/browser verification against a temporary database.
- [x] Verify migration compatibility and PostgreSQL if a runtime is available; record any unavailable check accurately.
- [x] Review the complete branch against the spec, address material findings, and record results.
- [x] Commit verified implementation and documentation; leave the branch ready for review without merging or publishing.
