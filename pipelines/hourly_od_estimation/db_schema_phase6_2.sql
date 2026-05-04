CREATE TABLE IF NOT EXISTS hourly_bus_stop_passenger (
    use_ym VARCHAR(6),
    route_no VARCHAR(50),
    route_name VARCHAR(100),
    stop_id VARCHAR(50),
    stop_ars VARCHAR(5),
    stop_name VARCHAR(200),
    hour INT,
    boarding_passengers DOUBLE PRECISION,
    alighting_passengers DOUBLE PRECISION,
    reg_ymd VARCHAR(8),
    PRIMARY KEY (use_ym, route_no, stop_ars, hour)
);

CREATE TABLE IF NOT EXISTS month_day_count (
    use_ym VARCHAR(6) PRIMARY KEY,
    mon INT,
    tue INT,
    wed INT,
    thu INT,
    fri INT,
    sat INT,
    sun_holiday INT
);

CREATE TABLE IF NOT EXISTS dow_hourly_ratio (
    route_no VARCHAR(50),
    stop_ars VARCHAR(5),
    day_type VARCHAR(20),
    hour INT,
    avg_boarding_passengers DOUBLE PRECISION,
    boarding_ratio DOUBLE PRECISION,
    method VARCHAR(50),
    PRIMARY KEY (route_no, stop_ars, day_type, hour)
);

CREATE TABLE IF NOT EXISTS estimated_hourly_od (
    기준일자 VARCHAR(8),
    노선명 VARCHAR(50),
    승차_정류장ars VARCHAR(5),
    하차_정류장ars VARCHAR(5),
    hour INT,
    estimated_passengers DOUBLE PRECISION,
    method VARCHAR(50),
    PRIMARY KEY (기준일자, 노선명, 승차_정류장ars, 하차_정류장ars, hour, method)
);

CREATE INDEX IF NOT EXISTS idx_hourly_bus_stop_passenger_route_stop
ON hourly_bus_stop_passenger (route_no, stop_ars, use_ym);

CREATE INDEX IF NOT EXISTS idx_dow_hourly_ratio_route_stop_day
ON dow_hourly_ratio (route_no, stop_ars, day_type);

CREATE INDEX IF NOT EXISTS idx_estimated_hourly_od_date_route
ON estimated_hourly_od (기준일자, 노선명);
