-- Validate new curated mapping coverage.
-- Use this after loading integrated_bus_stop_location and bus_stop_demand_node_mapping.

SELECT
    COUNT(*) AS total_bus_rows,
    COUNT(old_b."정류장번호") AS old_matched_rows,
    COUNT(new_b.canonical_node_id) AS new_matched_rows,
    COUNT(*) - COUNT(old_b."정류장번호") AS old_missing_rows,
    COUNT(*) - COUNT(new_b.canonical_node_id) AS new_missing_rows,
    ROUND(((COUNT(*) - COUNT(old_b."정류장번호"))::numeric / COUNT(*)) * 100, 2) AS old_missing_rate_percent,
    ROUND(((COUNT(*) - COUNT(new_b.canonical_node_id))::numeric / COUNT(*)) * 100, 2) AS new_missing_rate_percent
FROM integrated_hourly_transit_demand_light d
LEFT JOIN bus_stop_location old_b
  ON LPAD(d.node_id::text, 5, '0') = LPAD(old_b."정류장번호"::text, 5, '0')
LEFT JOIN bus_stop_demand_node_mapping m
  ON d.service_id = m.service_id
 AND d.node_id = m.demand_node_id
LEFT JOIN integrated_bus_stop_location new_b
  ON m.canonical_node_id = new_b.canonical_node_id
WHERE d.mode = 'bus';

-- Route-level new missing rate.
SELECT
    d.service_id,
    COUNT(*) AS total_rows,
    COUNT(new_b.canonical_node_id) AS new_matched_rows,
    COUNT(*) - COUNT(new_b.canonical_node_id) AS new_missing_rows,
    ROUND(((COUNT(*) - COUNT(new_b.canonical_node_id))::numeric / COUNT(*)) * 100, 2) AS new_missing_rate_percent
FROM integrated_hourly_transit_demand_light d
LEFT JOIN bus_stop_demand_node_mapping m
  ON d.service_id = m.service_id
 AND d.node_id = m.demand_node_id
LEFT JOIN integrated_bus_stop_location new_b
  ON m.canonical_node_id = new_b.canonical_node_id
WHERE d.mode = 'bus'
GROUP BY d.service_id
HAVING COUNT(*) - COUNT(new_b.canonical_node_id) > 0
ORDER BY new_missing_rate_percent DESC, new_missing_rows DESC;
