-- Run privately before traffic, on the disposable qualification database only.
-- Read-only exact counts can be expensive at 60M/180M rows; do not include them in timed load.
BEGIN TRANSACTION READ ONLY;
SET LOCAL statement_timeout = '30min';
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM campus_users
             WHERE email !~ '^load-[0-9]+@example[.]test$' AND email <> 'seed-template@example.test') THEN
    RAISE EXCEPTION 'Capacity inventory requires exclusively synthetic qualification accounts';
  END IF;
END $$;
WITH histories AS (SELECT owner_id, count(*) AS records FROM applications GROUP BY owner_id)
SELECT json_build_object(
  'accounts', (SELECT count(*) FROM campus_users WHERE email ~ '^load-[0-9]+@example[.]test$'),
  'applications', (SELECT count(*) FROM applications),
  'stages', (SELECT count(*) FROM interview_stages),
  'largestHistory', (SELECT coalesce(max(records),0) FROM histories),
  'historiesAtLeast10000', (SELECT count(*) FROM histories WHERE records >= 10000),
  'activeApplications', (SELECT count(*) FROM applications WHERE deleted = false),
  'observedAt', CURRENT_TIMESTAMP
);
COMMIT;
