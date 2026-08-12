-- run_all.sql
-- Create the complete SeoulTransitPlatform schema in dependency order.
--
-- Usage (run from repository root):
--   psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/schema/run_all.sql
--
-- This creates structure only. It does not load any data.
-- See docs/deployment/database_setup.md for the data loading order.

\set ON_ERROR_STOP on

-- Column names in this schema are Korean, so the client encoding must be
-- UTF8. On a Korean Windows install psql defaults to UHC and fails with:
--   ERROR: character with byte sequence 0xa4 0x80 in encoding "UHC" has no
--   equivalent in encoding "UTF8"
-- Setting it here means the script works without exporting PGCLIENTENCODING.

SET client_encoding = 'UTF8';

\echo '=== 00 extensions ==='
\ir 00_extensions.sql

\echo '=== 01 curated OD + anomaly + holiday (required for API server boot) ==='
\ir 01_curated_od.sql

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

\echo '=== 07 performance indexes ==='
\ir 07_performance_indexes.sql

\echo '=== schema creation complete ==='
\echo 'Expect 18 tables: 16 project tables + anomaly_data + PostGIS spatial_ref_sys'
