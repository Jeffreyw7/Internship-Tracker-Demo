-- Executed by the schema owner, after Flyway, in a JDBC transaction.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM campus_runtime;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM campus_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM campus_retention;
GRANT USAGE ON SCHEMA public TO campus_runtime;
GRANT SELECT, INSERT ON TABLE public.campus_users TO campus_runtime;
GRANT UPDATE (theme_preference, email_verified_at, password_hash, credential_version, reset_request_cutoff) ON TABLE public.campus_users TO campus_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.email_verification_tokens, public.email_outbox, public.auth_rate_limits TO campus_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE public.applications TO campus_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.interview_stages TO campus_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.password_reset_requests, public.password_reset_tokens TO campus_runtime;
GRANT SELECT, INSERT ON TABLE public.auth_security_events TO campus_runtime;
GRANT SELECT ON TABLE public.password_reset_control TO campus_runtime;
GRANT UPDATE (health_intake_paused) ON TABLE public.password_reset_control TO campus_runtime;
GRANT USAGE ON SEQUENCE public.auth_security_event_order TO campus_runtime;
-- Retention needs predicate columns for an age-bounded purge; it cannot read account pseudonyms.
GRANT USAGE ON SCHEMA public TO campus_retention;
GRANT SELECT (id, occurred_at), DELETE ON TABLE public.auth_security_events TO campus_retention;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.application_imports, public.application_import_quota_locks, public.application_import_rates TO campus_runtime;
REVOKE ALL ON TABLE public.flyway_schema_history FROM campus_runtime;
