-- Read-only. Result (2026-11-05): outside Seoul, 950,326 stop rows match the integrated
-- node; 2,069 carry another outside stop's code, 154 a Seoul stop's, 527 an unknown one.
-- ARS 00000 (11 different nodes in the master) carried 277102436 on 10,404 boarding rows
-- at about ten different depots.
-- Read-only. For stops whose ARS is not in bus_stop_location, checks the stored
-- standard code against integrated_bus_stop_location.canonical_node_id.
-- Also checks the one ARS that maps to several nodes in bus_stop_location.
SET client_encoding = 'UTF8';

CREATE TEMP TABLE m AS
  SELECT 정류장번호 AS ars, COUNT(DISTINCT 노드id) AS nodes, MIN(노드id) AS node
  FROM bus_stop_location GROUP BY 1;
ANALYZE m;

CREATE TEMP TABLE integ AS
  SELECT ars_id AS ars, COUNT(DISTINCT canonical_node_id) AS nodes, MIN(canonical_node_id) AS node
  FROM integrated_bus_stop_location WHERE ars_id IS NOT NULL GROUP BY 1;
ANALYZE integ;

CREATE TEMP TABLE integ_node AS SELECT DISTINCT node FROM integ WHERE nodes = 1;
CREATE TEMP TABLE seoul_node AS SELECT DISTINCT node FROM m WHERE nodes = 1;
ANALYZE integ_node;
ANALYZE seoul_node;

CREATE TEMP TABLE sides AS
  SELECT 승차_정류장ars AS ars, 승차_정류장표준코드 AS code, 승객수 AS pax FROM analysis_table_final
  UNION ALL
  SELECT 하차_정류장ars, 하차_정류장표준코드, 승객수 FROM analysis_table_final;

\echo '--- format of integrated node ids (length, sample)'
SELECT LENGTH(node) AS len, COUNT(*) AS ars, MIN(node) AS sample FROM integ GROUP BY 1 ORDER BY 2 DESC;

\echo '--- stops not in the Seoul master: stored code vs integrated node'
SELECT
  CASE WHEN i.ars IS NULL THEN 'not_in_integrated'
       WHEN i.nodes > 1 THEN 'integrated_multi_node'
       WHEN s.code IS NULL OR s.code = '' THEN 'no_code'
       WHEN s.code = i.node THEN 'match'
       WHEN j.node IS NOT NULL THEN 'code_of_other_integrated_stop'
       WHEN k.node IS NOT NULL THEN 'code_of_seoul_stop'
       ELSE 'unknown_code' END AS result,
  COUNT(*) AS stop_rows, SUM(s.pax) AS pax, COUNT(DISTINCT s.ars) AS distinct_ars
FROM sides s
LEFT JOIN m ON m.ars = s.ars
LEFT JOIN integ i ON i.ars = s.ars
LEFT JOIN integ_node j ON j.node = s.code
LEFT JOIN seoul_node k ON k.node = s.code
WHERE m.ars IS NULL AND s.ars ~ '^[0-9]{5}$'
GROUP BY 1 ORDER BY 2 DESC;

\echo '--- the ARS with several nodes in the Seoul master'
SELECT m.ars, m.nodes,
  (SELECT string_agg(DISTINCT 노드id, ',') FROM bus_stop_location b WHERE b.정류장번호 = m.ars) AS master_nodes,
  s.code, COUNT(*) AS stop_rows
FROM m JOIN sides s ON s.ars = m.ars
WHERE m.nodes > 1
GROUP BY 1, 2, 3, 4 ORDER BY 5 DESC;
