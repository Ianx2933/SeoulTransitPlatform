CREATE TABLE IF NOT EXISTS subway_hourly_station_demand_light (
    line_name VARCHAR(50),
    station_name VARCHAR(100),
    day_type VARCHAR(20),
    hour INT,
    boarding DOUBLE PRECISION,
    alighting DOUBLE PRECISION,
    source VARCHAR(50),
    PRIMARY KEY (line_name, station_name, day_type, hour)
);

CREATE TABLE IF NOT EXISTS integrated_hourly_transit_demand_light (
    mode VARCHAR(20),
    service_id VARCHAR(50),
    node_id VARCHAR(100),
    node_name VARCHAR(100),
    day_type VARCHAR(20),
    hour INT,
    boarding DOUBLE PRECISION,
    alighting DOUBLE PRECISION,
    source VARCHAR(50),
    PRIMARY KEY (mode, service_id, node_id, day_type, hour)
);

CREATE INDEX IF NOT EXISTS idx_subway_hourly_station_demand_light_lookup
ON subway_hourly_station_demand_light (line_name, station_name, day_type);

CREATE INDEX IF NOT EXISTS idx_integrated_hourly_transit_demand_light_mode_hour
ON integrated_hourly_transit_demand_light (mode, day_type, hour);
