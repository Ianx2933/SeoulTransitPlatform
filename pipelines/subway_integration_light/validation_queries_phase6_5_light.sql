-- 1. Subway lightweight row count
SELECT COUNT(*) AS subway_light_rows
FROM subway_hourly_station_demand_light;

-- 2. Integrated row count by mode
SELECT mode, COUNT(*) AS row_count
FROM integrated_hourly_transit_demand_light
GROUP BY mode
ORDER BY mode;

-- 3. Hourly boarding/alighting distribution by mode
SELECT mode, hour, SUM(boarding) AS total_boarding, SUM(alighting) AS total_alighting
FROM integrated_hourly_transit_demand_light
GROUP BY mode, hour
ORDER BY mode, hour;

-- 4. Top subway stations by boarding
SELECT service_id, node_name, SUM(boarding) AS total_boarding
FROM integrated_hourly_transit_demand_light
WHERE mode = 'subway'
GROUP BY service_id, node_name
ORDER BY total_boarding DESC
LIMIT 20;

-- 5. Top bus stops by boarding
SELECT service_id, node_id, SUM(boarding) AS total_boarding
FROM integrated_hourly_transit_demand_light
WHERE mode = 'bus'
GROUP BY service_id, node_id
ORDER BY total_boarding DESC
LIMIT 20;
