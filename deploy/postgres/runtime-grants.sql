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
-- Missing worker roles fail this transaction; administer upgrade-workers.sql before deployment.
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM campus_mail_worker, campus_export_worker;
GRANT USAGE ON SCHEMA public TO campus_mail_worker, campus_export_worker;
GRANT SELECT (id,email,email_verified_at,email_verification_exempt) ON public.campus_users TO campus_mail_worker;
GRANT SELECT (id,user_id,email,expires_at,consumed_at,revoked_at) ON public.email_verification_tokens TO campus_mail_worker;
GRANT DELETE ON public.email_verification_tokens TO campus_mail_worker;
GRANT SELECT, DELETE ON public.email_outbox TO campus_mail_worker;
GRANT UPDATE (status,payload,key_id,nonce,lease_owner,lease_expires_at,attempts,next_attempt_at,failure_category,sent_at)
 ON public.email_outbox TO campus_mail_worker;
GRANT SELECT (scope,subject_hash,window_start,expires_at), DELETE ON public.auth_rate_limits TO campus_mail_worker;
GRANT SELECT, INSERT ON public.export_jobs TO campus_runtime;
GRANT UPDATE (state,lease_owner,lease_expires_at) ON public.export_jobs TO campus_runtime;
GRANT SELECT ON public.export_jobs TO campus_export_worker;
GRANT UPDATE (state,lease_owner,lease_expires_at,attempts,object_key,byte_count,failure_code,next_attempt_at) ON public.export_jobs TO campus_export_worker;
GRANT DELETE ON public.export_jobs TO campus_export_worker;
GRANT SELECT, INSERT, UPDATE, DELETE ON public.export_objects TO campus_export_worker;
GRANT SELECT (id,owner_id,company,role,location,status,applied_date,deadline,follow_up_date,notes,deleted,created_at) ON public.applications TO campus_export_worker;
GRANT SELECT (id,application_id,position,name,stage_date,state) ON public.interview_stages TO campus_export_worker;
