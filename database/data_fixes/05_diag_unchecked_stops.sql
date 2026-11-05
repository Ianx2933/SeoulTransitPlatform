-- Read-only. Classifies stops whose ARS cannot be checked against bus_stop_location.
-- Result (2026-11-05): 7.9% of stop rows, 3.8% of passengers; 1,596 of 2,080 such
-- ARS are in integrated_bus_stop_location (stops outside Seoul), 473 are in neither.
-- Read-only. Classifies stops in analysis_table_final whose ARS cannot be checked
-- against bus_stop_location (ARS missing from the master or mapped to several nodes).
--   kind: ars_empty | ars_not_5_digits | multi_node | not_in_master
--   in_integrated: whether integrated_bus_stop_location knows the ARS
SET client_encoding = 'UTF8';

CREATE TEMP TABLE m AS
  SELECT 정류장번호 AS ars, COUNT(DISTINCT 노드id) AS nodes
  FROM bus_stop_location GROUP BY 1;
ANALYZE m;

CREATE TEMP TABLE integ AS
  SELECT DISTINCT ars_id AS ars FROM integrated_bus_stop_location WHERE ars_id IS NOT NULL;
ANALYZE integ;

CREATE TEMP TABLE sides AS
  SELECT 승차_정류장ars AS ars, 승차_정류장표준코드 AS code, 승객수 AS pax FROM analysis_table_final
  UNION ALL
  SELECT 하차_정류장ars, 하차_정류장표준코드, 승객수 FROM analysis_table_final;

\echo '--- unchecked stops by kind'
SELECT
  CASE WHEN s.ars IS NULL OR s.ars = '' THEN 'ars_empty'
       WHEN s.ars !~ '^[0-9]{5}$' THEN 'ars_not_5_digits'
       WHEN m.nodes > 1 THEN 'multi_node'
       ELSE 'not_in_master' END AS kind,
  CASE WHEN s.code IS NULL OR s.code = '' THEN 'no_code' ELSE 'has_code' END AS code,
  CASE WHEN i.ars IS NOT NULL THEN 'yes' ELSE 'no' END AS in_integrated,
  COUNT(*) AS stop_rows,
  SUM(s.pax) AS pax,
  COUNT(DISTINCT s.ars) AS distinct_ars
FROM sides s
LEFT JOIN m ON m.ars = s.ars
LEFT JOIN integ i ON i.ars = s.ars
WHERE m.ars IS NULL OR m.nodes > 1
GROUP BY 1, 2, 3 ORDER BY 4 DESC;

\echo '--- share of all stop rows and passengers'
SELECT
  ROUND(100.0 * COUNT(*) FILTER (WHERE m.ars IS NULL OR m.nodes > 1) / COUNT(*), 1) AS pct_rows,
  ROUND(100.0 * SUM(s.pax) FILTER (WHERE m.ars IS NULL OR m.nodes > 1) / SUM(s.pax), 1) AS pct_pax
FROM sides s LEFT JOIN m ON m.ars = s.ars;
