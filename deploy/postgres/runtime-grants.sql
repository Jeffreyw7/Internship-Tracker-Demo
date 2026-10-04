-- Executed by the schema owner, after Flyway, in a JDBC transaction.
REVOKE ALL ON SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM campus_runtime;
GRANT USAGE ON SCHEMA public TO campus_runtime;
GRANT SELECT, INSERT ON TABLE public.campus_users TO campus_runtime;
GRANT UPDATE (theme_preference, email_verified_at) ON TABLE public.campus_users TO campus_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.email_verification_tokens, public.email_outbox, public.auth_rate_limits TO campus_runtime;
GRANT SELECT, INSERT, UPDATE ON TABLE public.applications TO campus_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE public.interview_stages TO campus_runtime;
REVOKE ALL ON TABLE public.flyway_schema_history FROM campus_runtime;
