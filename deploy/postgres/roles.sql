-- Run only as part of the surrounding provisioning/upgrade transaction, by an admin.
-- psql SQL-literal interpolation safely handles quotes in passwords.
\getenv migration_password MIGRATION_DATABASE_PASSWORD
\getenv runtime_password RUNTIME_DATABASE_PASSWORD
CREATE ROLE campus_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'migration_password';
CREATE ROLE campus_runtime LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'runtime_password';
REVOKE ALL ON DATABASE campus FROM PUBLIC;
GRANT CONNECT ON DATABASE campus TO campus_migrator, campus_runtime;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
ALTER SCHEMA public OWNER TO campus_migrator;
\ir worker-roles.sql
