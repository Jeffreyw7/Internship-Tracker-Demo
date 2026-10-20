\set ON_ERROR_STOP on
DO $$ BEGIN
 IF EXISTS (SELECT FROM pg_roles WHERE rolname='campus_runtime' AND (rolsuper OR rolcreatedb OR rolcreaterole OR rolreplication OR rolbypassrls OR rolinherit)) THEN RAISE EXCEPTION 'Unsafe runtime role flags'; END IF;
 IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='campus_runtime' AND rolcanlogin) THEN RAISE EXCEPTION 'Runtime login missing'; END IF;
 IF EXISTS (SELECT FROM pg_auth_members WHERE member='campus_runtime'::regrole) THEN RAISE EXCEPTION 'Runtime has role memberships'; END IF;
 IF EXISTS (SELECT FROM pg_class WHERE relowner='campus_runtime'::regrole) OR EXISTS (SELECT FROM pg_namespace WHERE nspowner='campus_runtime'::regrole) OR EXISTS (SELECT FROM pg_database WHERE datdba='campus_runtime'::regrole) THEN RAISE EXCEPTION 'Runtime owns database objects'; END IF;
 IF has_database_privilege('campus_runtime','campus','CREATE') OR has_database_privilege('campus_runtime','campus','TEMP') OR has_schema_privilege('campus_runtime','public','CREATE') THEN RAISE EXCEPTION 'Runtime can create objects'; END IF;
 IF NOT has_database_privilege('campus_runtime','campus','CONNECT') OR NOT has_schema_privilege('campus_runtime','public','USAGE') THEN RAISE EXCEPTION 'Runtime cannot connect/use schema'; END IF;
 IF has_table_privilege('campus_runtime','public.flyway_schema_history','SELECT,INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') THEN RAISE EXCEPTION 'Runtime can access migration history'; END IF;
 IF has_table_privilege('campus_runtime','public.campus_users','UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_table_privilege('campus_runtime','public.applications','DELETE,TRUNCATE,REFERENCES,TRIGGER') OR has_table_privilege('campus_runtime','public.interview_stages','TRUNCATE,REFERENCES,TRIGGER') THEN RAISE EXCEPTION 'Excess runtime table privileges'; END IF;
 IF NOT has_table_privilege('campus_runtime','public.campus_users','SELECT') OR NOT has_table_privilege('campus_runtime','public.campus_users','INSERT')
 OR NOT has_table_privilege('campus_runtime','public.applications','SELECT') OR NOT has_table_privilege('campus_runtime','public.applications','INSERT') OR NOT has_table_privilege('campus_runtime','public.applications','UPDATE')
 OR NOT has_table_privilege('campus_runtime','public.interview_stages','SELECT') OR NOT has_table_privilege('campus_runtime','public.interview_stages','INSERT') OR NOT has_table_privilege('campus_runtime','public.interview_stages','UPDATE') OR NOT has_table_privilege('campus_runtime','public.interview_stages','DELETE') THEN RAISE EXCEPTION 'Missing runtime DML privileges'; END IF;
END $$;
DO $$ BEGIN
 -- A comma-separated privilege argument tests ANY privilege; test each required grant separately.
 IF EXISTS (
  SELECT FROM (VALUES ('public.application_imports'),('public.application_import_quota_locks'),('public.application_import_rates')) AS tables(name)
  CROSS JOIN (VALUES ('SELECT'),('INSERT'),('UPDATE'),('DELETE')) AS privileges(name)
  WHERE NOT has_table_privilege('campus_runtime',tables.name,privileges.name)
 ) THEN RAISE EXCEPTION 'Missing import DML privileges'; END IF;
 IF has_table_privilege('campus_runtime','public.application_imports','TRUNCATE,REFERENCES,TRIGGER') OR has_table_privilege('campus_runtime','public.application_import_quota_locks','TRUNCATE,REFERENCES,TRIGGER') OR has_table_privilege('campus_runtime','public.application_import_rates','TRUNCATE,REFERENCES,TRIGGER') THEN RAISE EXCEPTION 'Excess import privileges'; END IF;
END $$;
DO $$ BEGIN
 IF NOT has_column_privilege('campus_runtime','public.campus_users','theme_preference','UPDATE') OR NOT has_column_privilege('campus_runtime','public.campus_users','email_verified_at','UPDATE') THEN RAISE EXCEPTION 'Missing appearance or verification update privileges'; END IF;
 IF has_column_privilege('campus_runtime','public.campus_users','email','UPDATE') OR has_column_privilege('campus_runtime','public.campus_users','password_hash','UPDATE') OR has_column_privilege('campus_runtime','public.campus_users','email_verification_exempt','UPDATE') THEN RAISE EXCEPTION 'Runtime can alter protected account fields'; END IF;
 IF EXISTS (
  SELECT FROM (VALUES ('public.email_verification_tokens'),('public.email_outbox'),('public.auth_rate_limits')) AS tables(name)
  CROSS JOIN (VALUES ('SELECT'),('INSERT'),('UPDATE'),('DELETE')) AS privileges(name)
  WHERE NOT has_table_privilege('campus_runtime',tables.name,privileges.name)
 ) THEN RAISE EXCEPTION 'Missing verification DML privileges'; END IF;
END $$;
