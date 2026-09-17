\set ON_ERROR_STOP on
BEGIN;
DO $$ BEGIN
 IF current_database() <> 'campus' THEN RAISE EXCEPTION 'Expected campus database'; END IF;
 IF EXISTS (SELECT FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind IN ('r','p','S','v','m','f')) THEN
  RAISE EXCEPTION 'Database is not empty; use the explicit existing-volume procedure';
 END IF;
END $$;
\ir roles.sql
COMMIT;
