-- Retire the dated OD table (2026-11).
--
-- analysis_table_final held the OD days the API served, three of them inflated
-- four to fourteen times by repeated loads. The API now serves typical days from
-- od_typical_route_stop (09_od_typical_route_stop.sql). The old table is kept,
-- under a name that says so, for the locked odcorrection endpoints, the
-- prediction feature service (switched off) and the XGBoost training script,
-- whose model was trained on it and is not to be trusted.
--
-- Run once on each database (local and Cloud SQL), then deploy the API built
-- from the same commit: the API validates its entities against the schema on
-- start, so code and table name have to change together. Safe to run twice.
DO $$
BEGIN
    IF to_regclass('public.analysis_table_final') IS NOT NULL
       AND to_regclass('public.analysis_table_final_legacy') IS NULL THEN
        ALTER TABLE public.analysis_table_final RENAME TO analysis_table_final_legacy;
    END IF;
    IF to_regclass('public.idx_analysis_table_final_date_route') IS NOT NULL THEN
        ALTER INDEX public.idx_analysis_table_final_date_route
            RENAME TO idx_analysis_table_final_legacy_date_route;
    END IF;
    IF to_regclass('public.idx_analysis_table_final_route_seq') IS NOT NULL THEN
        ALTER INDEX public.idx_analysis_table_final_route_seq
            RENAME TO idx_analysis_table_final_legacy_route_seq;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'analysis_table_final_pkey') THEN
        ALTER TABLE public.analysis_table_final_legacy
            RENAME CONSTRAINT analysis_table_final_pkey TO analysis_table_final_legacy_pkey;
    END IF;
END $$;

SELECT to_regclass('public.analysis_table_final')        AS old_name_gone_if_null,
       to_regclass('public.analysis_table_final_legacy') AS legacy_table;
