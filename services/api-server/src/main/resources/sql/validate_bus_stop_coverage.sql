-- 1) Unique unmatched bus stop IDs under the old Seoul-centered join.
SELECT
    d.node_id,
    d.node_name,
    COUNT(DISTINCT d.service_id) AS route_count,
    COUNT(*) AS row_count
FROM integrated_hourly_transit_demand_light d
LEFT JOIN bus_stop_location b
  ON LPAD(d.node_id::text, 5, '0') = LPAD(b."정류장번호"::text, 5, '0')
WHERE d.mode = 'bus'
  AND b."정류장번호" IS NULL
GROUP BY d.node_id, d.node_name
ORDER BY route_count DESC, row_count DESC;

-- 2) Route-level missing rate under the old Seoul-centered join.
SELECT
    d.service_id,
    COUNT(*) AS total_rows,
    COUNT(b."정류장번호") AS matched_rows,
    COUNT(*) - COUNT(b."정류장번호") AS missing_rows,
    ROUND(((COUNT(*) - COUNT(b."정류장번호"))::numeric / COUNT(*)) * 100, 2) AS missing_rate_percent
FROM integrated_hourly_transit_demand_light d
LEFT JOIN bus_stop_location b
  ON LPAD(d.node_id::text, 5, '0') = LPAD(b."정류장번호"::text, 5, '0')
WHERE d.mode = 'bus'
GROUP BY d.service_id
HAVING COUNT(*) - COUNT(b."정류장번호") > 0
ORDER BY missing_rate_percent DESC, missing_rows DESC;
