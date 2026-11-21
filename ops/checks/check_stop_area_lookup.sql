-- Checks 10_stop_area_lookup.sql against the per-request placement it replaces.
-- Run after creating the views (read only):
--   $env:PGCLIENTENCODING = "UTF8"
--   & $psql ... -f ops\checks\check_stop_area_lookup.sql -o $HOME\Downloads\check_stop_area_lookup.txt
\timing on

\echo '== 1. Sizes'
SELECT (SELECT count(*) FROM stop_area_any)       AS stop_area_any_rows,
       (SELECT count(DISTINCT ars) FROM stop_area_any) AS stop_area_any_ars,
       (SELECT count(*) FROM stop_area_primary)   AS stop_area_primary_rows,
       (SELECT count(*) FROM dong_point)          AS dong_point_rows,
       (SELECT count(DISTINCT adm_cd) FROM public.admin_dong_boundary) AS boundary_units;

\echo '== 2. Area stops, old (ST_Covers per request) vs new (stop_area_any): rows only in one side, must be 0'
WITH areas(kind, lo, hi) AS (
    VALUES ('dong 11010530', '11010530', '11010530'),
           ('dong 11680640', '11680640', '11680640'),
           ('dong 11230730', '11230730', '11230730'),
           ('gu 11010',      '11010',    '11011'),
           ('gu 11680',      '11680',    '11681')
),
old AS (
    SELECT a.kind, s.ars
    FROM areas a
    CROSS JOIN LATERAL (
        SELECT LPAD("정류장번호"::text, 5, '0') AS ars
        FROM bus_stop_location b,
             (SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary
              WHERE CASE WHEN length(a.lo) = 8 THEN adm_cd = a.lo ELSE adm_cd >= a.lo AND adm_cd < a.hi END) dong
        WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)))
          AND LPAD("정류장번호"::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
        UNION
        SELECT LPAD(b.ars_id::text, 5, '0')
        FROM integrated_bus_stop_location b,
             (SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary
              WHERE CASE WHEN length(a.lo) = 8 THEN adm_cd = a.lo ELSE adm_cd >= a.lo AND adm_cd < a.hi END) dong
        WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)))
          AND LPAD(b.ars_id::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
        UNION
        SELECT f.ars
        FROM stop_location_fix f,
             (SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary
              WHERE CASE WHEN length(a.lo) = 8 THEN adm_cd = a.lo ELSE adm_cd >= a.lo AND adm_cd < a.hi END) dong
        WHERE ST_Covers(dong.g, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326))
    ) s
),
new AS (
    SELECT DISTINCT a.kind, s.ars
    FROM areas a
    JOIN stop_area_any s
      ON CASE WHEN length(a.lo) = 8 THEN s.adm_cd = a.lo ELSE s.adm_cd >= a.lo AND s.adm_cd < a.hi END
)
SELECT kind,
       count(*) FILTER (WHERE n.ars IS NULL) AS only_old,
       count(*) FILTER (WHERE o.ars IS NULL) AS only_new,
       count(*) FILTER (WHERE o.ars IS NOT NULL AND n.ars IS NOT NULL) AS both_sides
FROM old o FULL JOIN new n USING (kind, ars)
GROUP BY kind ORDER BY kind;

\echo '== 3. Legs of 11010530 (weekday, both): old SQL, then new SQL'
EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON, COSTS OFF)
WITH dong AS (
    SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary WHERE adm_cd = '11010530'
),
stops AS MATERIALIZED (
    SELECT LPAD("정류장번호"::text, 5, '0') AS ars
    FROM bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)))
      AND LPAD("정류장번호"::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT LPAD(b.ars_id::text, 5, '0')
    FROM integrated_bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)))
      AND LPAD(b.ars_id::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT f.ars FROM stop_location_fix f, dong
    WHERE ST_Covers(dong.g, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326))
),
stop_keys AS MATERIALIZED (
    SELECT ars FROM stops WHERE ars <> '00000' UNION SELECT LTRIM(ars, '0') FROM stops WHERE ars <> '00000'
)
SELECT 노선명, 승차_정류장ars, 하차_정류장ars, 승차_정류장순번, 하차_정류장순번, SUM(승객수)
FROM od_typical_route_stop
WHERE 기준일자 = 'typical_weekday' AND 승차_정류장ars IN (SELECT ars FROM stop_keys)
GROUP BY 1, 2, 3, 4, 5
UNION
SELECT 노선명, 승차_정류장ars, 하차_정류장ars, 승차_정류장순번, 하차_정류장순번, SUM(승객수)
FROM od_typical_route_stop
WHERE 기준일자 = 'typical_weekday' AND 하차_정류장ars IN (SELECT ars FROM stop_keys)
GROUP BY 1, 2, 3, 4, 5;

EXPLAIN (ANALYZE, BUFFERS, SUMMARY ON, COSTS OFF)
WITH stops AS MATERIALIZED (
    SELECT DISTINCT ars FROM stop_area_any WHERE adm_cd = '11010530'
),
stop_keys AS MATERIALIZED (
    SELECT ars FROM stops WHERE ars <> '00000' UNION SELECT LTRIM(ars, '0') FROM stops WHERE ars <> '00000'
)
SELECT 노선명, 승차_정류장ars, 하차_정류장ars, 승차_정류장순번, 하차_정류장순번, SUM(승객수)
FROM od_typical_route_stop
WHERE 기준일자 = 'typical_weekday' AND 승차_정류장ars IN (SELECT ars FROM stop_keys)
GROUP BY 1, 2, 3, 4, 5
UNION
SELECT 노선명, 승차_정류장ars, 하차_정류장ars, 승차_정류장순번, 하차_정류장순번, SUM(승객수)
FROM od_typical_route_stop
WHERE 기준일자 = 'typical_weekday' AND 하차_정류장ars IN (SELECT ars FROM stop_keys)
GROUP BY 1, 2, 3, 4, 5;
