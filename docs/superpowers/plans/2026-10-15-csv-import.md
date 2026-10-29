# CSV Import Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development. The user approved the design and explicitly authorized planning and execution without another approval pause. Independent implementation streams may run in parallel with disjoint file ownership.

**Goal:** Deliver the reviewed, accessible CSV add/update workflow with immutable review previews, atomic commits and durable recovery.

**Architecture:** React components use an import workflow hook and typed adapters. Application commits share useApplications' mutation lifecycle. Spring services parse bounded uploads, materialize owner-bound immutable reviews, and commit the reviewed batch and receipt in one transaction.

**Tech stack:** React 19/strict TypeScript, Spring Boot 3.5/Java 17, Commons CSV and Commons FileUpload streaming, Flyway, H2 and PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-10-15-csv-import-design.md`; review resolutions in `docs/superpowers/reviews/2026-10-15-csv-import-design-review.md`.

## Global constraints

- Preserve editable DTO whitelists, authenticated ownership, ordinary If-Match CRUD, soft delete/undo, serialized mutations and full paginated history.
- File 2 MiB, 500 data records, 32 columns, options 256 KiB, canonical preview 8 MiB, receipt 128 KiB; ignored/excluded data counts toward limits.
- Preview 30-minute TTL; minimal completion receipts 30 days. No automatic write replay. Account/lifetime/revision guards apply before dispatch and state effects.
- Keep existing business normalization/date rules and share explicit nested Bean Validation with bulk operations. Stage IDs are server-only in imports.
- Fixed-length HTTP/1.1 uploads only initially, header-only pre-CSRF rejection, admission before body parsing, bounded streaming multipart and elapsed upload deadlines.
- Atomic predecessor supersession, row-locked terminal expiry, durable reservations until safe release, one aggregate quota snapshot.
- Never alter applied migrations or use a user database for tests. Do not commit credentials/build outputs. Keep changes reviewable in the existing CSV-import feature checkout.

## Shared wire and Java integration contracts

All enums below use their shown JSON labels. New import Java classes are in `com.campus.tracker.application.importing`.

Preview POST accepts FormData `file` plus `options` JSON, binding strictly to:

`{schemaVersion:1,mapping:Record<string,string>,dateFormat:"ISO"|"MDY"|"DMY",rows:RowOverride[],supersedesPreviewId?:string|null}`.

Mapping keys are source column indices as strings. Destinations: `company`, `role`, `location`, `status`, `appliedDate`, `deadline`, `followUpDate`, `notes`, `stages`, `applicationId`, `legacyStages`, `ignore`.

`RowOverride = {rowNumber:number,action?:"Add"|"Update"|"Skip"|null,targetId?:string|null,values?:Record<string,string>,clears?:string[],stageMode?:"KEEP"|"REPLACE",acknowledgeLegacy?:boolean}`. Values override source text (including applicationId and stages); clears are optional editable fields only.

`Issue = {code:string,field:string,message:string}`. `Counts = {added:number,updated:number,skipped:number,unchanged:number,errors:number}`.

`Candidate = {id:string,company:string,role:string,location:string}`. `Intent = {intent:"KEEP"|"SET"|"CLEAR",value:string|null}`.

`PreviewRow = {rowNumber:number,cells:string[],action:"Add"|"Update"|"Skip"|"Unchanged",targetId:string|null,targetVersion:number|null,targetSummary:Candidate|null,candidates:Candidate[],moreCandidates:boolean,intents:Record<string,Intent>,before:EditableRecord|null,after:EditableRecord|null,differences:{field:string,before:string,after:string}[],issues:Issue[],warnings:string[]}`.

`EditableRecord` is ApplicationRequest's JSON shape (label enums, dates null or ISO, ordered stages). Server snapshots may contain server stage IDs for preservation. Uploaded stages JSON accepts only name/date/state.

`Preview = {id:string,schemaVersion:1,policyVersion:string,createdAt:string,expiresAt:string,reviewDigest:string,committable:boolean,headers:string[],rows:PreviewRow[],counts:Counts,issues:Issue[]}`.

`CommitRequest = {reviewDigest:string,acknowledgement:{existingApplicationsToUpdate:number}}`.

`Receipt = {importId:string,receiptId:string,completedAt:string,retainUntil:string,counts:Counts,rows:{rowNumber:number,action:string,applicationId:string|null,resultVersion:number|null}[]}`.

`Status = {status:"PREVIEW"|"COMPLETED"|"SUPERSEDED"|"UNRESOLVED",preview?:Preview|null,receipt?:Receipt|null,successorId?:string|null,code?:string|null}`. Preserve import-specific error codes and bounded row details. Runtime-validate envelopes.

Parser boundary: `CsvImportParser.parse(byte[] file) -> ParsedCsv(List<String> headers,List<List<String>> records,String sourceDigest)`. `ImportMultipartReader.read(HttpServletRequest) -> Upload(byte[] file,String optionsJson)` with redacted toString. Parsing/reader own their records and use bounded bytes; metadata orchestration owns wire DTOs.

Application boundary supplied by parent: `ApplicationRules.normalizeAndValidate(ApplicationRequest) -> ApplicationRequest`, `ApplicationRules.validate(ApplicationRequest) -> void`; `ApplicationService.lockForImport(UUID ownerId,Collection<UUID> ids) -> Map<UUID,ApplicationView>` in caller transaction, active/owner-scoped sorted locks. Existing create/update participate in the outer transaction and invoke explicit shared validation. `ApplicationRules.fromView(ApplicationView) -> ApplicationRequest` may be added for reuse.

## Review focus

1. A response body that fails after successful commit must retain recovery and never invite a replacement write (Task 3 + final HTTP tests).
2. A commit crossing TTL must retain both status recovery and quota reservation (Task 2 concurrency tests).
3. More than five correction cycles must remain possible without erasing committing imports (Task 2/3).
4. Missing CSRF and dripping chunk framing must never acquire upload capacity or spool parts (Task 1 real-container tests).
5. Stage-only edits and preserved stages must still honor parent versions and identity (Task 0/2).

## Task 0: Shared application validation and import lock boundary (parent)

**Files:** new `application/ApplicationRules.java`; modify `ApplicationService.java`, `ApplicationRepository.java`; test `ApplicationRulesTest.java` and application integration checks.

- [x] Write failing tests for direct service validation of nested/length/null inputs and preservation of ordinary normalization/business dates.
- [x] Run the targeted tests and observe missing validation failures.
- [x] Extract rules; apply explicit Bean Validation and business rules from all saves; expose sorted owner-scoped target locks without exposing entity fields.
- [x] Run relevant tests, review the diff, and publish integration signatures to backend implementer.

## Task 1: Bounded CSV/multipart transport and early security (parallel agent A)

**Ownership:** `CsvImportParser.java`, `ImportMultipartReader.java`, import upload/filter/configuration classes, `SecurityConfig.java`, `backend/pom.xml`, import configuration in application YAML, dedicated parser/transport tests. Do not edit orchestration/DTO/storage classes.

- [x] Add tests first for quoted/multiline/BOM/UTF-8/structural CSV and exact/overflow boundaries including ignored columns.
- [x] Confirm failures, then implement Commons CSV parsing with full EOF/501st-row detection, header/reserved-field checks, strict limits and redacted failures.
- [x] Add real-container tests first for absent/invalid CSRF, transport rejection, actual multipart and stalled/dripping bodies.
- [x] Implement streaming multipart decoding, pre-CSRF header guard, no-store envelope, post-authorization bounded permits/rates, supported transport/deadline enforcement and bounded in-memory part cleanup (documented adjustment); bypass standard servlet multipart resolution for this route.
- [x] Pin released compatible parser dependencies and run targeted tests. Report exact behavior/limits and integration instructions.

## Task 2: Preview semantics and durable atomic lifecycle (parallel agent B)

**Ownership:** remaining `application/importing` DTO/semantic/orchestration/persistence/controller classes, V4 migration, import grants/verify SQL, dedicated lifecycle tests. Do not edit parser/transport classes, ApplicationService/Rules or frontend.

- [x] Write failing semantic tests for mapping/defaults/KEEP/SET/CLEAR, target lookup, duplicate targets, candidate-only matching, validation, legacy stages and replacement identity.
- [x] Implement the shared wire contract and semantic projection using ApplicationRules and server snapshots. Never accept imported metadata/stage IDs.
- [x] Write failing lifecycle tests for rollback, same-ID duplicate commits, stale/deleted/cross-owner targets, expiry crossing, supersession and lost successor response, cleanup and policy changes.
- [x] Implement JDBC or JPA metadata persistence, immutable canonical payload, deterministic review digest, sorted locks, single outer transaction and minimal receipt. Supersession/cleanup must use owner/global admission locks and consistent aggregate accounting; locks/timeouts and schema CHECKs must match the spec.
- [x] Add V4 and least-privilege grants; add concurrent quota/reservation and maximum receipt tests. Status terminal expiry must lock/reread and return UNRESOLVED on contention.
- [x] Run targeted tests, publish DTO signatures promptly, and report integration details and remaining proof obligations.

## Task 3: Accessible frontend, adapters and serialized recovery (parallel agent C)

**Ownership:** `src/**`, frontend `tests/*.test.mjs` for import/transport, CSS. Do not edit backend or package/dependency configuration without coordinating with parent.

- [x] Add failing tests for typed multipart/commit/status, runtime validation, literal-safe export, session guards and unknown outcomes before implementation.
- [x] Implement import types, export helpers, typed adapters and workflow state with revision invalidation and predecessor recovery.
- [x] Integrate commit with useApplications' synchronous lock; preserve unresolved recovery across remount; guard every dispatch/page/response; separate committed-refresh-failed from import failure.
- [x] Build file/mapping/review/correction/confirmation/results dialog beside Export. Include per-field clear, row exclusion, explicit candidate selection, stages/legacy disclosures, before/after, exact update count, keyboard/focus/errors/status announcements and paginated review.
- [x] Add Application ID/structured stages exports and safe downloadable reports. Run npm.cmd test and npm.cmd run build.

## Task 4: Integration, browser/HTTP/PostgreSQL and independent review (parent)

**Ownership:** `tests/http_smoke.mjs`/Python runner, backend integration/Postgres tests, docs/API.md, docs/CODE_GUIDE.md, docs/DATABASE-SECURITY.md, VALIDATION.md, this plan/progress record; integration fixes coordinated with original owners.

- [x] Reconcile interfaces and run frontend/backend suites; reproduce and fix integration failures with regression tests.
- [x] Rebuild UI, package bundled app, run actual HTTP smoke including import/CSRF/idempotency/restart/status.
- [x] Run isolated real PostgreSQL fresh/upgrade/restricted-role import, supersession, commit, cleanup and concurrent admission tests.
- [ ] Actual browser visual/native-modal/keyboard/screen-reader verification is blocked: no browser is available. React/JSDOM focus/workflow and native Node FormData through Vite/bundled serving passed separately; real HTTP upload/deadline checks passed.
- [x] Dispatch three parallel independent reviewers with backend, security/resource and frontend/a11y scopes; wait for all, resolve findings, obtain focused rechecks and rerun affected checks.
- [x] Record commands/results/limits in VALIDATION.md, update public contracts and architecture/deployment guidance, verify diff and report completion with evidence.

## Execution rulings

- User explicitly requested independent parallel implementers, overriding the SDD skill's default sequential implementer rule; file ownership above prevents overlap.
- Current branch is CSV-import, not main/master. Work in place to preserve the approved uncommitted design artifacts and the user's feature checkout; no extra worktree/approval pause.
- Keep implementation changes uncommitted for review unless repository operations are explicitly needed; do not modify unrelated files or the user's database. Use the scoped progress ledger for compaction recovery.
- Windows wrapper and npm.ps1 fail in this environment; use npm.cmd and the cached Maven executable with an explicit workspace-local Maven repository under ignored `.superpowers/sdd/2026-10-15-csv-import/m2`; sandbox javac access required approved execution outside the sandbox. Never confuse launcher failures with test failures.

## Execution outcome

Feature implementation, three independent reviews, fixes and focused rechecks are complete. Final verification: 68 frontend tests, strict TypeScript/Vite build, 129 backend tests, two real PostgreSQL 18.6 fresh/upgrade scenarios, rebuilt bundled UI and real HTTP/restart smoke all passed. The final smoke includes native Node FormData direct/Vite proxy and discarded commit-response GET reconciliation. No tests failed or were skipped in those suites. Actual browser checks remain blocked as recorded above; PostgreSQL 17 and Java 17 runtime verification were not run. See VALIDATION.md and the consolidated implementation review for evidence and limits. The user subsequently authorized a descriptive Git commit and push on CSV-import.
