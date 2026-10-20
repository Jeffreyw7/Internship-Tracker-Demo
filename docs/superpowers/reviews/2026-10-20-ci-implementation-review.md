# CI Implementation Review — 2026-10-20

## Scope and reviewers

Reviewed the working-tree CI implementation against the approved CI design, current Maven profiles, Dockerfile, PostgreSQL integration tests and maintained smoke runner. Two fresh independent GPT-6.1 Sol High agents reviewed concurrently without edits or delegation; the parent waited for both final reports. The two earlier investigators and separate workflow/documentation implementers also completed their assignments.

## Consolidated findings by category

| Category | Review evidence / disposition |
| --- | --- |
| Correctness and reproducibility | No actionable findings. `.github/workflows/ci.yml:21` builds UI then verifies/packages backend and executes smoke from the same checkout. The Java17/PostgreSQL17 profiles, wrapper invocation and Vite development dependencies match repository behavior. |
| Test discovery and failure propagation | No actionable findings. `.github/scripts/check_ci.py:11` requires all three current PostgreSQL scenarios; `require_reports` rejects missing/empty/skipped/failed reports. `.github/workflows/ci.yml:164` always evaluates the required gate and accepts only exact success for both dependencies. |
| Workflow security | No actionable findings. `.github/workflows/ci.yml:9` sets read-only contents access; checkouts disable credential persistence. Official actions are pinned to verified release commit SHAs. Ordinary PR triggers, environment transport of needs JSON, no production secrets, and no publishing/deployment writes. |
| Resources, caches and diagnostics | No actionable findings. `.github/workflows/ci.yml:21` and `:102` bound independent jobs/long steps; superseded runs cancel. Caches contain downloaded dependencies only. Selected failure diagnostics have three-day retention; runtime probe containers use `--rm`. |
| Smoke cleanup | No actionable findings in the parent fix at `tests/http_smoke.mjs:320`: JVM shutdown errors now set a failing process exit code. The real smoke run passed after the change. |

The security reviewer independently reran all 16 helper tests successfully. The correctness reviewer's optional extra Python run encountered the WindowsApps shim, so it did not add test execution evidence; the parent independently ran all 16 tests successfully using bundled Python.

## Issues found and resolved before final review

- Node22/npm10 clean installation failed because the existing lockfile referenced missing `@emnapi/core` and `@emnapi/runtime` 1.10.0 records. `package-lock.json:204` adds only those records, preserving all existing package records and versions. Repeated `npm ci --include=dev` passed.
- The smoke cleanup handler previously logged JVM shutdown failures without failing CI. `tests/http_smoke.mjs:320` now sets exitCode1.
- Focused helper regression tests cover missing individual PostgreSQL scenarios, false aggregate report counters, skipped/failing/error cases, malformed inputs and exact-success job result/CLI behavior.

## Executed evidence and limits

Node22.23.3/npm10.9.9, actual Temurin17.0.20.1+1 and Maven3.9.11 on Windows: 68 frontend tests and 138 backend tests passed without skips; frontend build, bundled JAR packaging and maintained HTTP/SMTP/native multipart/Vite/reconciliation/JVM-restart smoke passed. All five current dist files matched their bundled JAR bytes. Actionlint1.7.12, 16 helper tests, seven multiline workflow Bash syntax checks, wrapper/provisioning shell syntax and smoke JavaScript syntax passed.

Actual Docker/Testcontainers discovery ran all three `PostgresPrivilegesIT` methods and returned three errors, zero skips, exit1 because Docker is unavailable. The real report guard rejected those failed reports with exit1. This proves failure propagation, not PostgreSQL correctness. Those checks could not run locally; hosted Docker evidence is recorded below.

The GitHub CLI credential lacks workflow scope, but the independently configured Git Credential Manager credential has workflow permission. An isolated CI-only branch and draft PR https://github.com/Jeffreyw7/Internship-Tracker-Demo/pull/1 were published without changing the current checkout HEAD/index or preexisting regression files. Hosted run https://github.com/Jeffreyw7/Internship-Tracker-Demo/actions/runs/39469068417 passed for CI snapshot 6e47873f0812b634cd0c6c446b3d91bc300ee705. No repository settings, images or deployments were changed. Use `docs/CI.md` for exact commands; `VALIDATION.md` records local evidence and the two existing npm audit advisories separately.

## Hosted verification

Ubuntu24.04 with actual Temurin17 ran 68 frontend tests, 136 committed backend unit tests and all three PostgreSQL17.11 Testcontainers integration tests without failures/errors/skips. The real report guard, packaged HTTP/SMTP/restart smoke, Compose configuration, migrate/app image builds, nonroot Java17 probes and CI required gate passed. Job times: application4m25s, containers3m21s, required6s. The local138 result additionally includes the two preserved uncommitted transaction-permit tests. The CI-only PR does not include them or their preexisting plan.

Final publication audit removed harmless trailing blank lines from both new helper files; no helper semantics changed. Documentation now records hosted evidence. Final head e4c23169fac13474d0ff84c93e7afe0ce3b1b44e passed hosted run https://github.com/Jeffreyw7/Internship-Tracker-Demo/actions/runs/39470045165: application4m45s, containers3m23s, required6s. Both hosted runs completed green; there are no remaining CI verification blockers. Full Compose rollout/browser checks remain outside this CI scope.

## Publication follow-up

After the CI-only runs above passed, the user requested commit and push. The remaining two-case transaction-permit regression class, its plan, and CI design/plan/review records are now included in the publication. Earlier 136-test hosted evidence applies to its exact linked revisions; the expanded head is covered by the recorded local138-test Java17 run and receives its own hosted PR checks.
