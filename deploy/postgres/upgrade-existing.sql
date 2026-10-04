\set ON_ERROR_STOP on
-- Stop the old application and verify a backup restore before executing this script.
-- Fail closed for unexpected schema contents or owners; investigate instead of widening it.
BEGIN;
DO $$
DECLARE object_name text;
BEGIN
 IF current_database() <> 'campus' THEN RAISE EXCEPTION 'Expected campus database'; END IF;
 IF EXISTS (SELECT FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind IN ('r','p','S','v','m','f') AND relname NOT IN ('campus_users','applications','interview_stages','flyway_schema_history')) THEN
  RAISE EXCEPTION 'Unexpected public schema objects; manual ownership inventory required';
 END IF;
 FOREACH object_name IN ARRAY ARRAY['campus_users','applications','interview_stages','flyway_schema_history'] LOOP
  IF NOT EXISTS (SELECT FROM pg_class WHERE relnamespace='public'::regnamespace AND relname=object_name AND relkind='r' AND relowner='campus'::regrole) THEN
   RAISE EXCEPTION 'Missing object or unexpected owner: %',object_name;
  END IF;
 END LOOP;
END $$;
\ir roles.sql
ALTER TABLE public.campus_users OWNER TO campus_migrator;
ALTER TABLE public.applications OWNER TO campus_migrator;
ALTER TABLE public.interview_stages OWNER TO campus_migrator;
ALTER TABLE public.flyway_schema_history OWNER TO campus_migrator;
\ir runtime-grants.sql
COMMIT;
