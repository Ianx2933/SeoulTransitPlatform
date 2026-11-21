-- Stop and district lookups for the district views (2026-11-20).
--
-- The district views used to place every bus stop in its 행정동 on each
-- request: ST_Union of the district polygons and ST_Covers of up to 25,000
-- stop points against admin_dong_boundary (3,624 rows, large geometries).
-- A load test on db-f1-micro hit the disk read limit at under one request a
-- second. Stops, boundaries and stop_location_fix change by hand, a few times
-- a year, so the placement is computed once here and read by index.
--
--   stop_area_any      every (ars, adm_cd) where any known point of the stop
--                      lies in the district: what AREA_STOPS selected
--                      (bus_stop_location and integrated_bus_stop_location,
--                      except ARS in stop_location_fix, plus the fix points).
--                      A stop on a shared edge is in both districts, as before.
--   stop_area_primary  one district per ARS from one point: stop_location_fix
--                      first, then bus_stop_location, then
--                      integrated_bus_stop_location; the lowest adm_cd on an
--                      edge. What the bus routes tab and bus-outside-Seoul used.
--   dong_point         ST_PointOnSurface of each district (all its rows), the
--                      point "how far" measures between.
--
-- Refresh after changing bus_stop_location, integrated_bus_stop_location,
-- stop_location_fix or admin_dong_boundary (each takes seconds):
--   REFRESH MATERIALIZED VIEW stop_area_any;
--   REFRESH MATERIALIZED VIEW stop_area_primary;
--   REFRESH MATERIALIZED VIEW dong_point;

CREATE MATERIALIZED VIEW IF NOT EXISTS stop_area_any AS
WITH pts AS (
    SELECT LPAD(b."정류장번호"::text, 5, '0') AS ars,
           COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)) AS g
    FROM bus_stop_location b
    WHERE LPAD(b."정류장번호"::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION ALL
    SELECT LPAD(b.ars_id::text, 5, '0'),
           COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326))
    FROM integrated_bus_stop_location b
    WHERE LPAD(b.ars_id::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION ALL
    SELECT f.ars, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326)
    FROM stop_location_fix f
)
SELECT DISTINCT p.ars, d.adm_cd
FROM pts p
JOIN public.admin_dong_boundary d ON ST_Covers(d.geom, p.g)
WHERE p.g IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_stop_area_any_adm ON stop_area_any (adm_cd, ars);

CREATE MATERIALIZED VIEW IF NOT EXISTS stop_area_primary AS
WITH pts AS (
    SELECT DISTINCT ON (ars) ars, g
    FROM (
        SELECT f.ars, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326) AS g, 0 AS pri
        FROM stop_location_fix f
        UNION ALL
        SELECT LPAD(b."정류장번호"::text, 5, '0'),
               COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)), 1
        FROM bus_stop_location b
        UNION ALL
        SELECT LPAD(b.ars_id::text, 5, '0'),
               COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)), 2
        FROM integrated_bus_stop_location b
    ) x
    WHERE g IS NOT NULL
    ORDER BY ars, pri
)
SELECT p.ars, d.adm_cd, d.adm_nm, p.g AS geom
FROM pts p
JOIN LATERAL (
    SELECT x.adm_cd, x.adm_nm
    FROM public.admin_dong_boundary x
    WHERE ST_Covers(x.geom, p.g)
    ORDER BY x.adm_cd
    LIMIT 1
) d ON true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_stop_area_primary_ars ON stop_area_primary (ars);

CREATE MATERIALIZED VIEW IF NOT EXISTS dong_point AS
SELECT adm_cd, ST_PointOnSurface(ST_Union(geom)) AS p
FROM public.admin_dong_boundary
GROUP BY adm_cd;

CREATE UNIQUE INDEX IF NOT EXISTS idx_dong_point_adm ON dong_point (adm_cd);

ANALYZE stop_area_any;
ANALYZE stop_area_primary;
ANALYZE dong_point;
