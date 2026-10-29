# CSV Import Review Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Close the confirmation resource-control gap and make runtime privilege verification require every necessary grant.

**Architecture:** Extend the existing decoded-route admission boundary to confirmation writes, reuse the process/owner limiter, and read confirmation JSON with incremental deadline checks. Keep database transactions, persisted reservations, and status reconciliation unchanged. Check required PostgreSQL privileges individually against the existing grant contract.

**Tech Stack:** Java 17, Spring Security/Tomcat, JUnit, PostgreSQL/psql.

**Spec:** User-authorized fixes for findings on commit `2b52a702bfcdae251834b22246689ec62e0ff104`; existing import contract in `docs/API.md`.

## Global Constraints

- Preserve authentication/CSRF before body access, strict JSON, the 4096-byte confirmation cap, and decoded route matching.
- Preserve status reads, durable receipts, atomic commits, owner isolation, reservation accounting, and existing user changes.
- Retain the documented fixed-length, uncompressed HTTP/1.1 import transport contract and 30-second deadline plus at most a 5-second blocked read.
- Never broaden runtime grants; PostgreSQL tests use their own disposable cluster.

## Review Focus

- Slow continuous and silent bodies must terminate and release work capacity.
- Encoded commit routes must enforce the same controls.
- A busy write must not block status reconciliation.
- Early auth/CSRF/size/transport rejection must avoid body reads.
- Removing any single required grant must make the real verifier fail.

### Task 1: Confirmation controls (parent)

**Files:** `ImportRequestFilter.java`, `ImportUploadPolicy.java`, `ImportWorkLimiter.java`, `ApplicationImportController.java`, new `ImportConfirmationReader.java` and `ImportConfirmationTransportServerTest.java` in the existing importing packages; `docs/API.md`.

- [x] Add real-server regressions for owner/process admission, auth/CSRF ordering, 4096-byte limits, encoded routes, slow continuous/stalled bodies, disconnects, malformed bodies, and status access during admission.
- [x] Run the focused tests before production edits and observe resource-control failures.
- [x] Add decoded commit classification and fixed-length transport checks, acquire shared permits before body access, and replace `readNBytes` with bounded incremental reads checking the request deadline.
- [x] Run focused tests, then backend suite and bundled HTTP/restart smoke checks. Update the documented shared concurrency scope.

### Task 2: Runtime privilege verifier (delegated, exclusive SQL/IT ownership)

**Files:** `deploy/postgres/verify-runtime.sql`, `backend/src/test/java/com/campus/tracker/PostgresPrivilegesIT.java`.

- [x] Add a real-PostgreSQL test exercising the verifier with complete grants and with each necessary privilege individually revoked and safely restored.
- [x] Observe the existing verifier incorrectly accepting an incomplete grant.
- [x] Require every necessary positive privilege separately, retaining prohibited checks and existing grants.
- [x] Pass focused PostgreSQL checks and existing isolated provisioning/upgrade/import scenarios.

### Integration review

- [x] Run independent read-only parallel reviews of confirmation controls and privilege verification; wait for both.
- [x] Validate and resolve actionable findings, recheck changed paths, and inspect final diff for unrelated changes.
- [x] Report exact checks/results and any missing evidence.
