-- 13_stop_ars_route_away.sql
-- Route stops whose ARS number belongs to another stop in the Seoul stop tables.
--
-- An ARS number is not unique across Seoul and Gyeonggi: a Seoul route's stop
-- in 안양, 성남 or 남양주 can carry the number of a Seoul stop 20-30 km away
-- (10193 is 방학동 in bus_stop_location and 평촌 on routes to 안양; 50 numbers
-- in the 2024-01 to 2026-11 route master). Looked up by number alone, riders at
-- such a stop land in the Seoul stop's 행정동. This view lists, per route, its
-- own stop (bus_route_stop_snapshot, latest snapshot) where that stop lies more
-- than 1 km from the stop_area_primary point of the same number, with the
-- stop's own 행정동 (NULL outside the boundary set). The API reads it wherever
-- the route is known: a listed (route, ARS) is not the area's stop, and its
-- district is the one here. Stop-table coordinate errors over 1 km show up too.
--
-- Needs 08 (bus_route_stop_snapshot) and 11 (stop_area_primary).
-- Refreshed by pipelines/bus_route_stop_snapshot/load_snapshot.sql; by hand:
--   REFRESH MATERIALIZED VIEW stop_ars_route_away;
CREATE MATERIALIZED VIEW IF NOT EXISTS stop_ars_route_away AS
WITH own AS (
    SELECT DISTINCT ON (route_nm, ars) route_nm, ars, node_nm, lat, lng
    FROM bus_route_stop_snapshot
    WHERE ars IS NOT NULL AND lat IS NOT NULL AND lng IS NOT NULL
    ORDER BY route_nm, ars, stdr_de DESC
),
far AS (
    SELECT o.route_nm, o.ars, o.node_nm, o.lat, o.lng,
           ST_SetSRID(ST_MakePoint(o.lng, o.lat), 4326) AS g
    FROM own o
    JOIN stop_area_primary p ON p.ars = o.ars
    WHERE ST_DistanceSphere(p.geom, ST_SetSRID(ST_MakePoint(o.lng, o.lat), 4326)) > 1000
)
SELECT f.route_nm, f.ars, f.node_nm, f.lat, f.lng, d.adm_cd, d.adm_nm
FROM far f
LEFT JOIN LATERAL (
    SELECT a.adm_cd, a.adm_nm
    FROM public.admin_dong_boundary a
    WHERE ST_Covers(a.geom, f.g)
    ORDER BY a.adm_cd
    LIMIT 1
) d ON true;

CREATE UNIQUE INDEX IF NOT EXISTS idx_stop_ars_route_away ON stop_ars_route_away (route_nm, ars);
CREATE INDEX IF NOT EXISTS idx_stop_ars_route_away_ars ON stop_ars_route_away (ars);

ANALYZE stop_ars_route_away;
