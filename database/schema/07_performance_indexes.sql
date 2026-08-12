-- 07_performance_indexes.sql
-- Phase 6.9-2 district demand performance indexes.
--
-- Run LAST. The index file also runs ANALYZE, and ANALYZE is only meaningful
-- once the tables hold data. On an empty database this still succeeds but the
-- planner statistics will be rebuilt when rows are loaded.
--
-- Measured effect (local): roughly 9.5-12.9 s -> about 192 ms cold call.
-- See docs/performance/phase6_9_2_district_demand_optimization.md

\ir ../performance/phase6_9_2_district_demand_indexes.sql