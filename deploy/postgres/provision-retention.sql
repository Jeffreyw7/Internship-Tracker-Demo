\set ON_ERROR_STOP on
-- Run once as database/role administrator for an already provisioned V3 installation,
-- before MigrationMain applies the V4 runtime and retention grants.
BEGIN;
DO $$ BEGIN
 IF current_database() <> 'campus' THEN RAISE EXCEPTION 'Expected campus database'; END IF;
 IF EXISTS (SELECT FROM pg_roles WHERE rolname='campus_retention' AND (NOT rolcanlogin OR rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)) THEN RAISE EXCEPTION 'Unsafe existing retention role'; END IF;
 IF EXISTS (SELECT FROM pg_auth_members m JOIN pg_roles r ON r.oid=m.member WHERE r.rolname='campus_retention') THEN RAISE EXCEPTION 'Retention role has memberships'; END IF;
END $$;
\getenv retention_password RETENTION_DATABASE_PASSWORD
SELECT format('CREATE ROLE campus_retention LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD %L', :'retention_password')
 WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname='campus_retention')
\gexec
GRANT CONNECT ON DATABASE campus TO campus_retention;
COMMIT;
