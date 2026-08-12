-- 06_demand_tables.sql
-- Hourly demand tables produced by the Phase 6.2 / 6.3 / 6.5 pipelines.
--
-- These DDL files already exist next to their pipelines. They are included
-- here rather than copied so there is exactly one definition of each table.
--
-- \ir resolves relative to THIS file's directory, so run with psql -f.

-- Phase 6.2: hourly_bus_stop_passenger, month_day_count,
--            dow_hourly_ratio, estimated_hourly_od
\ir ../../pipelines/hourly_od_estimation/db_schema_phase6_2.sql

-- Phase 6.3: dow_hourly_stop_pattern, estimated_hourly_stop_demand
\ir ../../pipelines/hourly_stop_pattern/db_schema_phase6_3_local.sql

-- Phase 6.5: subway_hourly_station_demand_light,
--            integrated_hourly_transit_demand_light
--
-- integrated_hourly_transit_demand_light is the central demand table for the
-- map APIs. Both node-catchment and district-demand read from it.
\ir ../../pipelines/subway_integration_light/db_schema_phase6_5_light.sql