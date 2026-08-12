-- 02_reference_bus_stop_location.sql
-- Seoul bus stop master reference table.
--
-- Source loader: pipelines/reference_loader/load_bus_stop_location.py
-- That loader creates the table itself, but it does NOT create the `geom`
-- column, while database/performance/phase6_9_2_district_demand_indexes.sql
-- builds a GIST index on bus_stop_location.geom. The geometry column was
-- added manually during development and is reproduced here so that a fresh
-- database matches the one the performance indexes were measured against.

CREATE TABLE IF NOT EXISTS public.bus_stop_location (
    노드id      VARCHAR(30) PRIMARY KEY,
    정류장번호   VARCHAR(10) NOT NULL,
    정류장명     TEXT        NOT NULL,
    경도        DOUBLE PRECISION,
    위도        DOUBLE PRECISION
);

-- Geometry column added separately so this file stays idempotent even on a
-- database created by the Python loader.
ALTER TABLE public.bus_stop_location
    ADD COLUMN IF NOT EXISTS geom geometry(Point, 4326);

CREATE INDEX IF NOT EXISTS idx_bus_stop_location_ars_name
ON public.bus_stop_location (정류장번호, 정류장명);

-- Populate geometry after loading rows.
--   UPDATE public.bus_stop_location
--   SET geom = ST_SetSRID(ST_MakePoint(경도, 위도), 4326)
--   WHERE 경도 IS NOT NULL AND 위도 IS NOT NULL;