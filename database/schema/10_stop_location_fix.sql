-- 10_stop_location_fix.sql
-- Manual bus stop positions; 11_stop_area_lookup.sql reads them.
--
-- Bus stop positions that override bus_stop_location and
-- integrated_bus_stop_location for the district queries (bus routes tab, road
-- load of an area). One row per ARS.
--
-- Why (2026-11-18, ops/stop_coords/check_stop_coords.sql): 101 ARS on the
-- stored route paths are in neither stop table (riders there had no district),
-- and some ARS numbers now belong to another stop than the one the stop table
-- still places (05170: 어린이대공원역 in the table, 복정파출소 on routes 302 and
-- 452 in TOPIS and the bus route API). Positions come from the route paths
-- (Seoul bus route API). Seed rows: ops/stop_coords/stop_location_fix_20261118.sql.

CREATE TABLE IF NOT EXISTS stop_location_fix (
    ars        text PRIMARY KEY,              -- 5 digits
    stop_name  text,
    lat        double precision NOT NULL,
    lng        double precision NOT NULL,
    reason     text NOT NULL,                 -- missing | ars_reused | manual
    note       text,
    created_at timestamptz NOT NULL DEFAULT now()
);
