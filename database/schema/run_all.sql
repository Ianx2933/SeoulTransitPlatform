-- run_all.sql
-- Create the complete SeoulTransitPlatform schema in dependency order.
--
-- Usage (run from repository root):
--   psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/schema/run_all.sql
--
-- This creates structure only. It does not load any data.
-- See docs/deployment/database_setup.md for the data loading order.
--
-- Files here build a new database. Changes to a database that already exists
-- live in database/migrations/ and are not run from this script: a new
-- database gets their result from the files below directly.

\set ON_ERROR_STOP on

-- Column names in this schema are Korean, so the client encoding must be
-- UTF8. On a Korean Windows install psql defaults to UHC and fails with:
--   ERROR: character with byte sequence 0xa4 0x80 in encoding "UHC" has no
--   equivalent in encoding "UTF8"
-- Setting it here means the script works without exporting PGCLIENTENCODING.

SET client_encoding = 'UTF8';

\echo '=== 00 extensions ==='
\ir 00_extensions.sql

\echo '=== 01 legacy OD + anomaly + holiday (required for API server boot) ==='
\ir 01_legacy_od_holiday.sql

\echo '=== 02 bus stop location reference ==='
\ir 02_reference_bus_stop_location.sql

\echo '=== 03 subway station location reference ==='
\ir 03_reference_subway_station_location.sql

\echo '=== 04 admin dong boundary ==='
\ir 04_reference_admin_dong_boundary.sql

\echo '=== 05 bus stop integration ==='
\ir 05_bus_stop_integration.sql

\echo '=== 06 demand tables (phase 6.2 / 6.3 / 6.5) ==='
\ir 06_demand_tables.sql

\echo '=== 07 representative district OD (transit-od-dong-load) ==='
\ir 07_od_dong_typical.sql

\echo '=== 08 bus route paths (transit-bus-route-path) ==='
\ir 08_bus_route_path.sql

\echo '=== 09 representative route stop OD (transit-od-typical-load; JPA entity) ==='
\ir 09_od_typical_route_stop.sql

\echo '=== 10 stop position fixes ==='
\ir 10_stop_location_fix.sql

\echo '=== 11 stop and district lookups (materialized views; needs 02, 04, 05, 10) ==='
\ir 11_stop_area_lookup.sql

\echo '=== 12 performance indexes (last: runs ANALYZE) ==='
\ir 12_performance_indexes.sql

\echo '=== schema creation complete ==='
\echo 'Expect 29 tables (28 project tables + PostGIS spatial_ref_sys) and 3 materialized views.'
\echo 'The materialized views are empty until data is loaded: after loading stops,'
\echo 'boundaries and stop_location_fix, run the REFRESH lines in 11_stop_area_lookup.sql.'
