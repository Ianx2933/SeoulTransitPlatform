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
--
-- Verified against the live database: columns, types, and the
-- canonical_node_id primary key all match.

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

-- Supporting indexes present in the live database.
--
-- Note: do NOT add a separate btree on canonical_node_id. The primary key
-- already provides one, and a second index on the same column is pure
-- overhead — it is maintained on every write and never chosen by the planner.

CREATE INDEX IF NOT EXISTS idx_integrated_bus_stop_location_ars_id
ON public.integrated_bus_stop_location (ars_id);

CREATE INDEX IF NOT EXISTS idx_integrated_bus_stop_location_stop_name
ON public.integrated_bus_stop_location (stop_name);

CREATE INDEX IF NOT EXISTS idx_integrated_bus_stop_location_geom
ON public.integrated_bus_stop_location USING GIST (geom);

-- Route-level mapping between demand node ids and canonical stop ids.
--
-- The foreign key below is present in the live database and enforces load
-- order: integrated_bus_stop_location must be populated first. It also means
-- the two tables cannot be truncated in arbitrary order — the mapping table
-- must be cleared before the location table.
--
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
    canonical_candidate_count  INTEGER,
    CONSTRAINT bus_stop_demand_node_mapping_canonical_node_id_fkey
        FOREIGN KEY (canonical_node_id)
        REFERENCES public.integrated_bus_stop_location (canonical_node_id)
);

-- Load rows with:
--   psql ... -f scripts/db/load_curated_bus_stop_mapping.psql
--
-- That script TRUNCATEs both tables, \copy-loads the curated CSVs from
-- data/processed/bus_stop_location/, and fills integrated_bus_stop_location.geom.
-- It must be run from the repository root because the \copy paths are relative.
