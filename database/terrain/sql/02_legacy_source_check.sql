-- Read-only checks against the existing master. No table is altered.
SELECT column_name, data_type FROM information_schema.columns
WHERE table_schema='public' AND table_name='bus_stop_location'
ORDER BY ordinal_position;
SELECT base_date, COUNT(*) AS polygon_rows, COUNT(DISTINCT adm_cd) AS distinct_codes
FROM public.admin_dong_boundary GROUP BY base_date ORDER BY base_date;
SELECT COUNT(*) AS master_rows, COUNT(DISTINCT "노드id"::text) AS distinct_ids,
       COUNT(*) FILTER (WHERE slope_pct IS NOT NULL) AS measured_rows
FROM public.bus_stop_location;
-- Confirm the exact ID namespace, source method, and boundary date before publishing.
