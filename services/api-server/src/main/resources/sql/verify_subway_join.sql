-- Verify unmatched subway demand rows after alias correction
-- (alias 보정 후 매칭되지 않는 지하철 수요 행을 검증한다.)

WITH resolved_alias AS (
    SELECT DISTINCT ON (d.service_id, d.node_name)
        d.service_id,
        d.node_name,
        a.target_line_name,
        a.target_station_name
    FROM integrated_hourly_transit_demand_light d
    LEFT JOIN subway_station_join_alias a
      ON d.service_id = a.source_line_name
     AND (
          d.node_name = a.source_station_name
          OR a.source_station_name = '*'
     )
    WHERE d.mode = 'subway'
    ORDER BY
        d.service_id,
        d.node_name,
        CASE
            WHEN a.source_station_name = d.node_name THEN 0
            WHEN a.source_station_name = '*' THEN 1
            ELSE 2
        END
)
SELECT
    d.service_id,
    d.node_name,
    COUNT(*) AS row_count
FROM integrated_hourly_transit_demand_light d
LEFT JOIN resolved_alias a
  ON d.service_id = a.service_id
 AND d.node_name = a.node_name
LEFT JOIN subway_station_location s
  ON COALESCE(a.target_line_name, d.service_id) = s.line_name
 AND regexp_replace(
        CASE
          WHEN a.target_station_name = '*' THEN d.node_name
          ELSE COALESCE(a.target_station_name, d.node_name)
        END,
        '\(.*\)',
        '',
        'g'
     ) = regexp_replace(s.station_name, '\(.*\)', '', 'g')
WHERE d.mode = 'subway'
  AND s.station_id IS NULL
GROUP BY d.service_id, d.node_name
ORDER BY row_count DESC;
