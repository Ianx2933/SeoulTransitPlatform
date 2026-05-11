-- ARS normalization rule:
-- All numeric ARS / demand_node_id values must be stored as 5-character strings.
-- Example: 1234 -> 01234. Non-numeric placeholders such as 0000~ are excluded from coordinate matching.

-- Curated-based bus stop coordinate integration schema.
-- This design uses canonical_node_id = node_info.노드ID / curated 정류장표준코드.

CREATE EXTENSION IF NOT EXISTS postgis;

DROP TABLE IF EXISTS bus_stop_demand_node_mapping;
DROP TABLE IF EXISTS integrated_bus_stop_location;

CREATE TABLE integrated_bus_stop_location (
    canonical_node_id VARCHAR(100) PRIMARY KEY,
    ars_id VARCHAR(20),
    stop_name VARCHAR(200),
    node_info_stop_name VARCHAR(200),
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION,
    region_id VARCHAR(50),
    source_region VARCHAR(100),
    source_file VARCHAR(200),
    match_method VARCHAR(100),
    match_confidence DOUBLE PRECISION,
    geom geometry(Point, 4326)
);

CREATE INDEX idx_integrated_bus_stop_location_ars_id
ON integrated_bus_stop_location (ars_id);

CREATE INDEX idx_integrated_bus_stop_location_stop_name
ON integrated_bus_stop_location (stop_name);

CREATE INDEX idx_integrated_bus_stop_location_geom
ON integrated_bus_stop_location
USING GIST (geom);

CREATE TABLE bus_stop_demand_node_mapping (
    service_id VARCHAR(100) NOT NULL,
    service_id_type VARCHAR(50),
    route_name VARCHAR(100),
    converted_route_id VARCHAR(100),
    demand_node_id VARCHAR(100) NOT NULL,
    canonical_node_id VARCHAR(100) NOT NULL REFERENCES integrated_bus_stop_location(canonical_node_id),
    ars_id VARCHAR(20),
    stop_name VARCHAR(200),
    node_info_stop_name VARCHAR(200),
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION,
    region_id VARCHAR(50),
    seq VARCHAR(50),
    side VARCHAR(20),
    match_method VARCHAR(100),
    match_confidence DOUBLE PRECISION,
    reference_rows BIGINT,
    passenger_sum BIGINT,
    canonical_candidate_count INTEGER,
    PRIMARY KEY (service_id, demand_node_id, canonical_node_id, side)
);

CREATE INDEX idx_bus_stop_demand_node_mapping_service_node
ON bus_stop_demand_node_mapping (service_id, demand_node_id);

CREATE INDEX idx_bus_stop_demand_node_mapping_demand_node
ON bus_stop_demand_node_mapping (demand_node_id);

CREATE INDEX idx_bus_stop_demand_node_mapping_canonical
ON bus_stop_demand_node_mapping (canonical_node_id);
