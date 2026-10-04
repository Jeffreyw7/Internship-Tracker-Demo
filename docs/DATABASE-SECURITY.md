# Database privilege separation

The production web process uses `campus_runtime`. The one-shot `migrate` container uses `campus_migrator` and invokes `MigrationMain` directly, without a Spring web/JPA context. Flyway and the packaged grants script must both succeed before Compose starts the app. H2 development still runs Flyway normally.

| Role | Scope |
| --- | --- |
| `campus_admin` | Provisioning, backup/restore, maintenance; never supplied to the app |
| `campus_migrator` | Owns the public schema and application tables; used only for migrations |
| `campus_runtime` | Database CONNECT, schema USAGE, users SELECT/INSERT and column UPDATE for appearance/verification, applications SELECT/INSERT/UPDATE, stages and verification/outbox/rate-limit tables SELECT/INSERT/UPDATE/DELETE |

Runtime has no privileged role flags or memberships, ownership, CREATE/TEMP, migration-history access, or user/application DELETE. Explicit grants are reapplied after every migration; future tables require an intentional grant change. This is an isolated application database: provisioning removes database/schema privileges inherited through PUBLIC.

Account updates are limited to `theme_preference` and `email_verified_at`; identity, password hashes, and verification exemptions remain protected. Hibernate updates only changed account columns so email confirmation respects these column grants.

## Fresh local stack

Copy `.env.example` to `.env` and replace each password placeholder with a distinct secret. Never commit `.env`. Run:

```sh
docker compose config --quiet
docker compose up --build -d
```

The PostgreSQL entrypoint invokes `provision.sql` only for an empty volume. Secrets reach psql through environment variables and SQL-literal quoting, rather than SQL concatenation or command-line password arguments. The database container necessarily has provisioning credentials; the app receives only its runtime password. The local app remains bound to loopback with local HTTP cookie settings. Public hosting requires its own HTTPS and secret-management configuration.

To force the one-shot migration to run again after changing migrations or grant policy:

```sh
docker compose stop app
docker compose up --build --force-recreate migrate
docker compose up -d app
```

Do not start the app when the migration command fails.

## Existing volume: explicit maintenance procedure

Do not remove the volume or run `docker compose down -v`. New POSTGRES environment settings do not change existing role passwords.

1. Stop the old app. Record the original image/version and database ownership/privilege inventory.
2. Take a PostgreSQL custom-format backup with the existing admin credentials, plus a protected role inventory. Restore it into a separate disposable PostgreSQL 17 database and verify application records before touching the original database.
3. For the original Compose installation, set `DATABASE_ADMIN_USERNAME=campus` and `DATABASE_ADMIN_PASSWORD` to its existing admin password. Set new, distinct migration and runtime passwords. The legacy `campus` role remains an administrator; it must no longer be supplied to the app.
4. Start only the database with the new Compose configuration so the scripts and new environment variables are available:

   ```sh
   docker compose up -d database
   docker compose exec -T database psql -X -U campus -d campus -v ON_ERROR_STOP=1 -f /opt/campus/postgres/upgrade-existing.sql
   ```

   The script is one transaction. It requires the four known tables to be owned by `campus` and rejects unexpected public tables/views/sequences or pre-existing target roles. On failure, investigate the inventory; do not widen grants or run a broad `REASSIGN OWNED`. Already-upgraded installations use the normal migration job, not this one-time script.
5. Run the migration job, then verify the effective runtime privileges:

   ```sh
   docker compose up --build --force-recreate migrate
   docker compose exec -T database psql -X -U campus -d campus -v ON_ERROR_STOP=1 -f /opt/campus/postgres/verify-runtime.sql
   ```

6. Only after success, start the app and verify registration, login, create/edit, soft-delete/restore, and stage removal. Compare record counts and representative values with the backup inventory.
7. Remove the legacy admin credential from any previous web deployments and rotate it through an administrative connection. Changing `.env` alone does not rotate PostgreSQL passwords.

Keep the app stopped if any step fails. Provisioning/upgrade errors roll back their transaction. Flyway migrations may already have committed when the subsequent grants transaction fails; fix the grants and rerun the job. For rollback after an incompatible schema change, restore the verified backup to a separate database and validate it before switching. Do not grant superuser privileges back to the web process as a workaround.

## Verification

From `backend`, run `./mvnw -Ppostgres-it verify` (Windows: `.\mvnw.cmd -Ppostgres-it verify`). The profile creates fresh, disposable PostgreSQL 17 containers and fails visibly if Docker is missing. It exercises the actual provisioning/upgrade scripts, repeated migrations, retained legacy data, allowed DML, and permission-denied SQLSTATE 42501 for prohibited operations. Ordinary H2 tests do not establish PostgreSQL privilege safety.

The optional profile verifies SQL privileges and application HTTP workflows, but does not prove Compose startup ordering. Validate the fresh and restored Compose stacks before deployment. Deliberately break a migration in a disposable checkout and verify that `app` remains stopped when `migrate` exits nonzero.

### Optional Windows verification without Docker

Supply an installed PostgreSQL 17 bin directory containing initdb.exe, pg_ctl.exe, postgres.exe, psql.exe and their runtime libraries:

```powershell
.\mvnw.cmd -B -Ppostgres-it '-Dpostgres.test.bin=C:/Program Files/PostgreSQL/17/bin' verify
```

This mode initializes isolated clusters under backend/target, uses loopback and synthetic credentials, and shuts the servers down after each test. It never connects to an existing application database. Subprocess output goes to files to avoid inherited Windows pipe handles blocking shutdown.

Each fresh/legacy scenario also starts the application with runtime credentials and exercises registration with samples, login, application create/edit/delete/restore, and stage removal over HTTP. See [the verification record](../VALIDATION.md) for executed results and remaining deployment checks.
