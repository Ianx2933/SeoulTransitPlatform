-- psql: -v dataset_id='your-existing-version' -f sql/04_activate_dataset.sql
-- No DELETE, TRUNCATE or replacement of source tables.
\set ON_ERROR_STOP on
BEGIN;
SELECT pg_advisory_xact_lock(718403, 20260907);
INSERT INTO public.terrain_active_dataset(singleton,dataset_id,activated_at)
VALUES (TRUE, :'dataset_id', now())
ON CONFLICT (singleton) DO UPDATE SET dataset_id=EXCLUDED.dataset_id, activated_at=EXCLUDED.activated_at;
COMMIT;
