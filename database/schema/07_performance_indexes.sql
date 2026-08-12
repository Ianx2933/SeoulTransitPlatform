-- 07_performance_indexes.sql
-- Phase 6.9-2 district demand performance indexes.
--
-- Run LAST. The index file also runs ANALYZE, and ANALYZE is only meaningful
-- once the tables hold data. On an empty database this still succeeds but the
-- planner statistics will be rebuilt when rows are loaded.
--
-- Measured effect (local): roughly 9.5-12.9 s -> about 192 ms cold call.
-- See docs/performance/phase6_9_2_district_demand_optimization.md

-- Duplicate index caution.
--
-- This file creates indexes that may already exist under different names on a
-- database built with ogr2ogr, because ogr2ogr and the table's primary key
-- create their own. Two indexes on the same column and method are redundant:
-- both are updated on every write, and the planner uses only one.
--
-- After running this file, check for duplicates and drop the extras:
--
--   SELECT tablename, indexname, indexdef
--   FROM pg_indexes
--   WHERE schemaname = 'public'
--   ORDER BY tablename, indexdef;
--
-- Known overlaps on a live database:
--   admin_dong_boundary_geom_geom_idx        vs idx_admin_dong_boundary_geom
--   integrated_bus_stop_location_pkey        vs idx_integrated_bus_stop_location_canonical

\ir ../performance/phase6_9_2_district_demand_indexes.sql
