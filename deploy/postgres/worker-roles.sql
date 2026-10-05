-- Included only by administrative provisioning/upgrade transactions.
\getenv mail_worker_password MAIL_WORKER_DATABASE_PASSWORD
\getenv export_worker_password EXPORT_WORKER_DATABASE_PASSWORD
DO $$ BEGIN
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='campus_mail_worker') THEN
  CREATE ROLE campus_mail_worker NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 END IF;
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='campus_export_worker') THEN
  CREATE ROLE campus_export_worker NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
 END IF;
 IF EXISTS (SELECT FROM pg_roles WHERE rolname IN ('campus_mail_worker','campus_export_worker')
   AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit))
   OR EXISTS (SELECT FROM pg_auth_members WHERE member IN ('campus_mail_worker'::regrole,'campus_export_worker'::regrole)) THEN
  RAISE EXCEPTION 'Unsafe existing worker roles';
 END IF;
 IF EXISTS (SELECT FROM pg_class WHERE relowner IN ('campus_mail_worker'::regrole,'campus_export_worker'::regrole))
   OR EXISTS (SELECT FROM pg_namespace WHERE nspowner IN ('campus_mail_worker'::regrole,'campus_export_worker'::regrole))
   OR EXISTS (SELECT FROM pg_database WHERE datdba IN ('campus_mail_worker'::regrole,'campus_export_worker'::regrole)) THEN
  RAISE EXCEPTION 'Workers must not own database objects';
 END IF;
END $$;
-- SQL-literal psql interpolation preserves quotes without constructing SQL from passwords.
SELECT length(:'mail_worker_password') >= 16 AND length(:'export_worker_password') >= 16
 AND :'mail_worker_password' <> :'export_worker_password' AS valid_worker_passwords \gset
\if :valid_worker_passwords
 ALTER ROLE campus_mail_worker LOGIN PASSWORD :'mail_worker_password';
 ALTER ROLE campus_export_worker LOGIN PASSWORD :'export_worker_password';
\else
 \echo 'Distinct worker passwords of at least 16 characters are required'
 \quit 1
\endif
REVOKE ALL ON DATABASE campus FROM campus_mail_worker, campus_export_worker;
GRANT CONNECT ON DATABASE campus TO campus_mail_worker, campus_export_worker;
