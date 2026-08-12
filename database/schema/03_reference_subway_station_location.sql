-- 03_reference_subway_station_location.sql
-- Subway station master and join-alias correction table.
--
-- Source loader: pipelines/metro_ingestion/load_subway_station_location.py
-- Alias data:    services/api-server/src/main/resources/sql/subway_station_join_alias.sql
--
-- Ordering matters: subway_station_join_alias.sql runs an UPDATE against
-- subway_station_location ('뚝섬유원지' -> '자양'), so the station table must
-- exist and be populated before the alias script is applied.

CREATE TABLE IF NOT EXISTS public.subway_station_location (
    station_id    VARCHAR(50)  PRIMARY KEY,
    station_name  VARCHAR(100),
    line_name     VARCHAR(100),
    lat           DOUBLE PRECISION,
    lng           DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_subway_station_location_line_station
ON public.subway_station_location (line_name, station_name);

-- Alias table structure only. The alias ROWS and the station-name UPDATE live
-- in the API server resource file and must be applied AFTER station data load.
--
--   psql ... -f services/api-server/src/main/resources/sql/subway_station_join_alias.sql

CREATE TABLE IF NOT EXISTS public.subway_station_join_alias (
    source_line_name     VARCHAR(100),
    source_station_name  VARCHAR(100),
    target_line_name     VARCHAR(100),
    target_station_name  VARCHAR(100),
    reason               VARCHAR(255),
    PRIMARY KEY (source_line_name, source_station_name)
);