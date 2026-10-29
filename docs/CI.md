# Continuous integration

The workflow in [`ci.yml`](../.github/workflows/ci.yml) checks pull requests, pushes to `main`, and manual `workflow_dispatch` runs. It has no path filters. It verifies source, packages the application, and builds local container images; it does not deploy or publish images.

## Jobs and the required check

| Job ID / displayed check | Coverage |
| --- | --- |
| `verify` / `Application verification` | Node 22 (22.18 or newer), actual Temurin Java 17, npm install/tests/production build, then one Maven `clean verify` with `with-ui,postgres-it`, required test-report checks, and the bundled HTTP smoke test |
| `containers` / `Container builds` | Docker/Compose preflight, Compose configuration validation with distinct synthetic credentials, builds of the `migrate` and `app` Dockerfile targets, and runtime user/Java checks |
| `required` / `CI required` | Runs after both work jobs and accepts only two exact `success` results |

The application and container jobs run in parallel on separate `ubuntu-24.04` runners. Frontend build, bundled JAR, and smoke inputs stay in one checkout in the application job. Neither job downloads a build artifact from a different run. The Maven wrapper is invoked with `sh ./mvnw` because its tracked file mode does not make it executable.

The `postgres-it` profile runs PostgreSQL 17 Testcontainers through Failsafe. CI requires all seven release scenarios without skips: fresh provisioning, legacy upgrade, V3 and V4 retention-role upgrades, rejection of missing DML privileges, backup/restore recovery, and restricted monitoring. Backend unit reports are also required; missing, empty, skipped or failing reports fail verification. Local runs can use explicitly selected PostgreSQL binaries, but hosted CI uses Testcontainers.

The Node smoke runner launches the just-built executable JAR, an SMTP capture server, and a Vite proxy with temporary H2 data. It exercises packaged assets, verification/authentication, application/import requests, and persistence through a JVM restart. It is HTTP evidence, not browser visual or keyboard-accessibility verification. Vite is dynamically imported, so **development dependencies must be installed**; `npm ci --omit=dev` is insufficient.

The Dockerfile deliberately verifies backend unit tests during its source build. That repeats those tests independently of the application job. Building both targets on the same Docker daemon reuses their shared frontend/backend stages. The container job does not start the Compose application/database/mail stack: successful parsing and image builds do not prove a full rollout, database health ordering, or migration-failure startup gating.

**Branch-protection recommendation:** require the stable `CI required` check. Its gate rejects failure, cancellation, skipping, or missing results from either dependency. Making this check required is a separate repository-settings action; this change does not alter branch protection.

## Trust, resource bounds, and diagnostics

The workflow uses `contents: read`, disables checkout credential persistence, and uses full commit SHA pins for its official GitHub actions. It uses ordinary `pull_request`, not privileged `pull_request_target` or `workflow_run`. No production secret, package write, deployment credential, or OIDC token is required. Fork pull requests can use the same synthetic test inputs, subject to the repository's normal Actions approval policy. Untrusted changes still execute code, so keep these checks on disposable GitHub-hosted runners.

Concurrency is scoped by workflow and pull-request number or ref, with superseded runs cancelled. Timeouts are 40 minutes for application verification, 35 minutes for container builds, and 5 minutes for the required gate. Long steps also have explicit caps: 5 minutes each for npm installation, frontend tests and frontend build; 25 minutes for Maven verification; 8 minutes for smoke; and 25 minutes per Docker build. Prerequisite/Compose checks have 2-minute caps and image-runtime checks have a 3-minute cap. The smoke runner owns its JVM, Vite/SMTP listeners and disposable database; hosted runner disposal also bounds resources after hard cancellation.

**Planning estimate:** roughly 5–15 minutes of wall time with the two work jobs running in parallel on a normal network. Cold dependency caches, image pulls and runner load can make it slower; this estimate is not a guaranteed range.

**First hosted observation:** [run 39469068417](https://github.com/Jeffreyw7/Internship-Tracker-Demo/actions/runs/39469068417) succeeded with application verification taking **4 minutes 25 seconds**, container builds **3 minutes 21 seconds**, and the required gate **6 seconds**. These are individual job durations from one run, not a guarantee for future runs. See [VALIDATION.md](../VALIDATION.md#hosted-verification) for the tested source commit and actual test/container results.

Only downloaded npm and Maven dependencies are cached, using the lockfile and Maven POM/wrapper inputs. `dist`, `backend/target`, JARs, databases, credentials and smoke state are not cached. Failure artifacts retain selected Surefire/Failsafe XML reports, the Failsafe summary, and smoke or container build/runtime transcripts for 3 days; they do not include a full workspace, environment dump, database directory or dependency cache. Treat diagnostics from untrusted pull requests as untrusted data.

See GitHub's [secure-use guidance](https://docs.github.com/en/actions/reference/security/secure-use) for token permissions, untrusted checkout risks and immutable action references. Action behavior is documented by [checkout](https://github.com/actions/checkout), [setup-node](https://github.com/actions/setup-node), [setup-java](https://github.com/actions/setup-java), and [upload-artifact](https://github.com/actions/upload-artifact).

## Reproduce application verification

Use Bash on Linux with Node **22.18 or newer in the 22.x line**, an actual JDK **17**, Python 3 (standard library only for the CI guard), and Docker with a running Linux-container daemon. Docker must be able to pull `postgres:17` and the Testcontainers support images. Set `JAVA_HOME` and `PATH` to that JDK; a newer JDK compiling with a Java 17 target does not reproduce the runtime check. The wrapper downloads Maven 3.9.11. Network access is also needed for npm/Maven dependencies on a cold cache.

Run from the repository root:

```bash
set -euo pipefail
node --version
java -version
docker info
python3 -m unittest discover -s .github/scripts -p 'test_*.py' -v

# Include Vite and the other development dependencies, even if NODE_ENV is set.
npm ci --include=dev
npm test
npm run build
test -s dist/index.html

# Do not set postgres.test.bin: that selects the separate local-binary harness.
(
  cd backend
  sh ./mvnw -B -ntp -Pwith-ui,postgres-it clean verify
)
python3 .github/scripts/check_ci.py reports backend

# Keep pipefail so tee cannot hide a smoke-test failure.
npm run test:smoke 2>&1 | tee smoke.log
```

The `with-ui` profile copies an existing `dist`; it does not build the frontend. Keep the build order above and retain the same checkout until smoke completes. The report guard above matches the workflow and rejects absent or unsuccessful required reports. A normal `mvnw test` without `postgres-it` does not run the required PostgreSQL integration suite.

On Windows PowerShell use `npm.cmd` and, if it works in the local environment, `.\mvnw.cmd` from `backend/`. If the wrapper fails before Maven launches, use Maven 3.9.11 directly with the same profiles and goals. Windows `postgres.test.bin` can select disposable locally installed PostgreSQL binaries, as recorded in historical validation, but that is separate evidence from CI's Docker/PostgreSQL 17 path. See [VALIDATION.md](../VALIDATION.md) for actual commands, runtime versions and limits of earlier runs.

## Reproduce container checks

In a separate checkout or after the application checks above, use Bash from the repository root with Docker BuildKit and Docker Compose v2 available. These synthetic passwords are only configuration inputs; no stack is started:

```bash
set -euo pipefail
export DATABASE_ADMIN_PASSWORD='ci-admin-only-synthetic-password'
export MIGRATION_DATABASE_PASSWORD='ci-migrator-only-synthetic-password'
export DATABASE_PASSWORD='ci-runtime-only-synthetic-password'
export DOCKER_BUILDKIT=1

docker info
docker compose version
docker compose --file compose.yaml config --quiet
docker build --progress=plain --target migrate --tag campus-ci-migrate .
docker build --progress=plain --target app --tag campus-ci-app .

for image in campus-ci-migrate campus-ci-app; do
  docker image inspect --format '{{.Config.User}}' "$image"
  test "$(docker run --rm --entrypoint id "$image" -u)" -ne 0
  docker run --rm --entrypoint java "$image" -version 2>&1 | tee "$image-java.log"
  grep -Eq 'version "17([.]|")' "$image-java.log"
done
```

Both version outputs must report Java 17. These commands do not launch the image's migration/server entrypoints, connect to a user database or change an existing Compose volume. Full Compose rollout and failure-gating verification remain separate checks; follow [DATABASE-SECURITY.md](DATABASE-SECURITY.md) before upgrading an existing database.
