-- 05_bus_stop_integration.sql
-- Metropolitan integrated bus stop location and demand-node mapping.
--
-- Phase 6.7 introduced these two tables to extend coverage beyond Seoul.
--
-- NOTE:
-- services/api-server/src/main/resources/sql/create_integrated_bus_stop_location.sql
-- is misleadingly named: despite the filename it creates only
-- bus_stop_demand_node_mapping. The integrated_bus_stop_location table itself
-- had no DDL anywhere in the repository and is reconstructed below from
-- scripts/db/load_curated_bus_stop_mapping.psql (its \copy column list plus
-- the geom UPDATE) and from the columns read by NodeCatchmentRepository and
-- DistrictDemandRepository.

CREATE TABLE IF NOT EXISTS public.integrated_bus_stop_location (
    canonical_node_id     VARCHAR(100) PRIMARY KEY,
    ars_id                VARCHAR(20),
    stop_name             VARCHAR(200),
    node_info_stop_name   VARCHAR(200),
    lat                   DOUBLE PRECISION,
    lng                   DOUBLE PRECISION,
    region_id             VARCHAR(50),
    source_region         VARCHAR(50),
    source_file           VARCHAR(255),
    match_method          VARCHAR(100),
    match_confidence      DOUBLE PRECISION,
    geom                  geometry(Point, 4326)
);

-- Route-level mapping between demand node ids and canonical stop ids.
CREATE TABLE IF NOT EXISTS public.bus_stop_demand_node_mapping (
    service_id                 VARCHAR(100),
    service_id_type            VARCHAR(50),
    route_name                 VARCHAR(200),
    converted_route_id         VARCHAR(100),
    demand_node_id             VARCHAR(20),
    canonical_node_id          VARCHAR(100),
    ars_id                     VARCHAR(20),
    stop_name                  VARCHAR(200),
    node_info_stop_name        VARCHAR(200),
    lat                        DOUBLE PRECISION,
    lng                        DOUBLE PRECISION,
    region_id                  VARCHAR(50),
    seq                        INTEGER,
    side                       VARCHAR(20),
    match_method               VARCHAR(100),
    match_confidence           DOUBLE PRECISION,
    reference_rows             INTEGER,
    passenger_sum              BIGINT,
    canonical_candidate_count  INTEGER
);

-- Load rows with:
--   psql ... -f scripts/db/load_curated_bus_stop_mapping.psql
--
-- That script TRUNCATEs both tables, \copy-loads the curated CSVs from
-- data/processed/bus_stop_location/, and fills integrated_bus_stop_location.geom.
-- It must be run from the repository root because the \copy paths are relative.