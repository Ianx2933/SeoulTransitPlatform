-- Stop coordinates the app uses vs. where the stored route paths put the stop.
--
-- The demand map and the district queries place a bus stop at
-- bus_stop_location (first) or integrated_bus_stop_location (fallback).
-- The road-load map draws the same stop from bus_route_path_station (the
-- Seoul bus route API). When the two are far apart, one of them is wrong
-- (e.g. 복정파출소, 2026-11-18), and the stop may also fall in the wrong
-- 행정동, which moves its riders to another district.
--
-- Read-only. One row per ARS whose used coordinate is missing or more than
-- :thr metres from the median of its route-path coordinates.
--
-- Run (psql 18, Korean identifiers: PGCLIENTENCODING=UTF8 first):
--   & $psql -h 127.0.0.1 -p 5433 -U postgres -d Seoul_Transit -q -v thr=150 --csv
--       -f ops\stop_coords\check_stop_coords.sql -o $HOME\Downloads\stop_coords_check.csv

SET statement_timeout = '120s';

WITH bsl AS (
    SELECT DISTINCT ON (ars) ars, nm, g
    FROM (
        SELECT LPAD("정류장번호"::text, 5, '0') AS ars, "정류장명" AS nm,
               COALESCE(geom, ST_SetSRID(ST_MakePoint("경도", "위도"), 4326)) AS g
        FROM bus_stop_location
    ) x
    WHERE ars <> '00000'
    ORDER BY ars, g IS NULL
),
ibs AS (
    SELECT DISTINCT ON (ars) ars, g
    FROM (
        SELECT LPAD(ars_id::text, 5, '0') AS ars,
               COALESCE(geom, ST_SetSRID(ST_MakePoint(lng, lat), 4326)) AS g
        FROM integrated_bus_stop_location
    ) x
    WHERE ars <> '00000'
    ORDER BY ars, g IS NULL
),
path AS (
    SELECT s.ars,
           MIN(s.station_nm) AS nm,
           COUNT(DISTINCT r.od_route_nm) AS n_routes,
           ST_SetSRID(ST_MakePoint(
               percentile_cont(0.5) WITHIN GROUP (ORDER BY s.lng),
               percentile_cont(0.5) WITHIN GROUP (ORDER BY s.lat)), 4326) AS g
    FROM bus_route_path_station s
    JOIN bus_route_path_route r ON r.route_nm = s.route_nm
    WHERE r.status = 'ok' AND s.ars IS NOT NULL AND s.lat IS NOT NULL AND s.lng IS NOT NULL
    GROUP BY s.ars
),
joined AS (
    SELECT p.ars, b.nm AS bsl_name, p.nm AS path_name, p.n_routes,
           CASE WHEN b.g IS NOT NULL THEN 'bus_stop_location'
                WHEN i.g IS NOT NULL THEN 'integrated'
                ELSE 'none' END AS source,
           COALESCE(b.g, i.g) AS used_g, p.g AS path_g, b.g AS bsl_g, i.g AS ibs_g
    FROM path p
    LEFT JOIN bsl b ON b.ars = p.ars
    LEFT JOIN ibs i ON i.ars = p.ars
),
flagged AS (
    SELECT j.*,
           ROUND(ST_DistanceSphere(j.used_g, j.path_g)::numeric) AS d_used_path_m,
           ROUND(ST_DistanceSphere(j.bsl_g, j.ibs_g)::numeric) AS d_bsl_ibs_m
    FROM joined j
    WHERE j.used_g IS NULL OR ST_DistanceSphere(j.used_g, j.path_g) > :thr
)
SELECT f.ars, f.bsl_name, f.path_name, f.n_routes, f.source,
       f.d_used_path_m, f.d_bsl_ibs_m,
       du.adm_cd AS dong_used_cd, du.adm_nm AS dong_used,
       dp.adm_cd AS dong_path_cd, dp.adm_nm AS dong_path,
       (du.adm_cd IS DISTINCT FROM dp.adm_cd) AS dong_differs,
       ROUND(ST_Y(f.used_g)::numeric, 6) AS used_lat, ROUND(ST_X(f.used_g)::numeric, 6) AS used_lng,
       ROUND(ST_Y(f.path_g)::numeric, 6) AS path_lat, ROUND(ST_X(f.path_g)::numeric, 6) AS path_lng
FROM flagged f
LEFT JOIN LATERAL (
    SELECT d.adm_cd, d.adm_nm FROM public.admin_dong_boundary d
    WHERE f.used_g IS NOT NULL AND ST_Covers(d.geom, f.used_g)
    ORDER BY d.adm_cd LIMIT 1
) du ON true
LEFT JOIN LATERAL (
    SELECT d.adm_cd, d.adm_nm FROM public.admin_dong_boundary d
    WHERE ST_Covers(d.geom, f.path_g)
    ORDER BY d.adm_cd LIMIT 1
) dp ON true
ORDER BY f.used_g IS NULL DESC, f.d_used_path_m DESC NULLS LAST;
