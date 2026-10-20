# CI Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development; parent owns integration and verification. User-approved execution needs no further planning approval.

**Goal:** Deliver reliable least-privilege GitHub Actions gates for frontend, Java17 backend, PostgreSQL17 integration, bundled smoke and both Docker targets.
**Architecture:** docs/superpowers/specs/2026-10-20-ci-design.md. Two independent work jobs plus fail-closed required gate, same-checkout frontend/JAR/smoke.
**Tech stack:** GitHub Actions, Node22, Temurin17, Maven wrapper, Docker/Testcontainers/PostgreSQL17, existing Node smoke.

## Constraints and review focus

Preserve existing uncommitted work; earlier AGENTS revoked. No application behavior, grants, migrations, dependency-version refresh, deployment or image publishing. Main is default branch. Do not use local Windows paths in CI. Missing Docker/IT reports, skipped required work, stale artifacts and masked pipeline exit codes must fail. Pin official actions to verified immutable SHAs, read-only token, no fork secrets. Only downloaded dependencies are cached. Keep runner and job bounds explicit.

### Task1: Investigation and design (parent + two read-only agents)
- [x] Verify source/docs/runtime/remote evidence; reconcile independent frontend/smoke and backend/PostgreSQL reports.
- [x] Record selected architecture, runtime/runner constraints, official sources, job dependencies and duplicate-work tradeoff.

### Task2: Workflow implementation (exclusive workflow agent)
**Own:** .github/workflows/ci.yml and narrowly needed .github/scripts/* CI helpers/tests.
- [x] Implement verify, containers and required jobs; safe triggers/concurrency; strict failure propagation, report-discovery checks and selected failure artifacts.
- [x] Cover success/failure/cancel/skip/missing results in required-gate validation and missing/zero/skipped/failed PostgreSQL report cases.
- [x] Validate workflow and helpers; report candidate files and exact checks. No edits to docs/production/build files or commits.

### Task3: Documentation (exclusive docs agent)
**Own:** docs/CI.md, README.md, VALIDATION.md.
- [x] Document graph/check names, exact portable reproduction commands, caches/artifacts/trust boundaries, fork behavior and known infrastructure requirements.
- [x] Preserve historical results and uncommitted transaction-permit evidence; correct stale builtins-only smoke claim.
- [x] Record only verified execution provided by parent; distinguish local validation, pending hosted runs, Docker/Compose parsing/builds and rollout proof.

### Task4: Parent integration and parallel independent reviews
- [x] Obtain portable actual Java17/Node22/actionlint tools if needed without global installation; verify official download checksums.
- [x] Validate YAML/action semantics/helpers and run installation/frontend/backend/package/smoke commands where possible; prove negative gates fail.
- [x] Inspect shared smoke cleanup failure behavior and make only a concrete justified CI-test correction if needed.
- [x] Launch fresh CI-correctness/reproducibility and security/resource reviewers concurrently; wait for both, validate findings, assign fixes to owners and recheck.
- [x] Run hosted workflow if access and existing authorization permit; otherwise report exact publication/infrastructure blocker after completing all independent work.
- [x] Final diff audit, documentation reconciliation and explicit check/report summary with reviewer dispositions and limits.

## Final verification status

Local implementation/reviews and real hosted Ubuntu/Docker verification completed successfully. See VALIDATION.md and docs/superpowers/reviews/2026-10-20-ci-implementation-review.md. Isolated draft PR1 was published without changing the original HEAD/index or preexisting uncommitted regression files. No image publication, deployment or repository policy changes. Final head e4c23169fac13474d0ff84c93e7afe0ce3b1b44e passed hosted run39470045165; all three jobs succeeded.
