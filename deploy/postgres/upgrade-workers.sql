\set ON_ERROR_STOP on
-- Stop affected processes; verify an isolated backup restore before changing a live installation.
BEGIN;
DO $$ BEGIN
 IF current_database() <> 'campus' THEN RAISE EXCEPTION 'Expected campus database'; END IF;
 IF NOT EXISTS (SELECT FROM pg_namespace WHERE nspname='public' AND nspowner='campus_migrator'::regrole) THEN
  RAISE EXCEPTION 'Expected previously separated migration ownership';
 END IF;
END $$;
\ir worker-roles.sql
COMMIT;
-- Run MigrationMain afterward to apply the intentionally narrow worker grants.
