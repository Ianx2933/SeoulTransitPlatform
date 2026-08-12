-- 00_extensions.sql
-- Required PostgreSQL extensions.
-- PostGIS is required by every spatial query in the map demand APIs
-- (ST_Contains, ST_DWithin, ST_SetSRID, ST_MakePoint, GIST indexes).

CREATE EXTENSION IF NOT EXISTS postgis;

-- Verify.
--   SELECT PostGIS_Version();