CREATE TABLE IF NOT EXISTS dow_hourly_stop_pattern (
    route_no VARCHAR(50),
    stop_ars VARCHAR(5),
    day_type VARCHAR(20),
    hour INT,
    avg_boarding_passengers DOUBLE PRECISION,
    avg_alighting_passengers DOUBLE PRECISION,
    boarding_ratio DOUBLE PRECISION,
    alighting_ratio DOUBLE PRECISION,
    method VARCHAR(50),
    PRIMARY KEY (route_no, stop_ars, day_type, hour)
);

CREATE TABLE IF NOT EXISTS estimated_hourly_stop_demand (
    route_no VARCHAR(50),
    stop_ars VARCHAR(5),
    day_type VARCHAR(20),
    hour INT,
    estimated_boarding DOUBLE PRECISION,
    estimated_alighting DOUBLE PRECISION,
    method VARCHAR(50),
    PRIMARY KEY (route_no, stop_ars, day_type, hour)
);

CREATE INDEX IF NOT EXISTS idx_dow_hourly_stop_pattern_lookup
ON dow_hourly_stop_pattern (route_no, stop_ars, day_type);

CREATE INDEX IF NOT EXISTS idx_estimated_hourly_stop_demand_lookup
ON estimated_hourly_stop_demand (route_no, stop_ars, day_type);
