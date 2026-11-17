-- BigQuery: district OD with admin_dong_boundary codes, one row per
-- (day_type, origin, destination). Rows outside Seoul collapse into 'outside'.
-- Run with --destination_table, then extract to GCS (see load_od_dong.md).
WITH codes AS (
  SELECT gu, dong, ANY_VALUE(adm_cd) AS adm_cd
  FROM dbt_dev.stg_stop_dong
  GROUP BY gu, dong
)
SELECT
  f.day_type,
  COALESCE(o.adm_cd, 'outside') AS origin_cd,
  ANY_VALUE(IF(o.adm_cd IS NULL, 'outside', f.origin_dong)) AS origin_name,
  COALESCE(d.adm_cd, 'outside') AS dest_cd,
  ANY_VALUE(IF(d.adm_cd IS NULL, 'outside', f.dest_dong)) AS dest_name,
  SUM(IFNULL(f.subway_rides, 0)) AS subway_rides,
  SUM(IFNULL(f.bus_rides, 0)) AS bus_rides,
  SUM(IFNULL(f.total_rides, 0)) AS total_rides
FROM dbt_dev.fct_od_dong_mode_typical f
LEFT JOIN codes o ON o.gu = f.origin_gu AND o.dong = f.origin_dong
LEFT JOIN codes d ON d.gu = f.dest_gu AND d.dong = f.dest_dong
GROUP BY f.day_type, origin_cd, dest_cd
