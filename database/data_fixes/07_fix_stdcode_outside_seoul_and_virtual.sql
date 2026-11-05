-- Applied to the local database and Cloud SQL on 2026-11-05, after file 04.
-- Result in both: backup 12,716 rows; outside Seoul 1,114 boarding and 1,737
-- alighting codes rewritten; ARS 00000: 0 matched by name, 10,064 boarding and
-- 376 alighting codes cleared; 0, 0, 0 remaining.
-- Two leftovers of the position-based join, found after file 04 by files 05 and 06.
--
-- 1. Stops outside Seoul are not in bus_stop_location, so file 04 left them alone.
--    integrated_bus_stop_location covers them (its node IDs agree with the stored
--    code for 950,326 of about 953,000 rows). Codes are rewritten from it where
--    the ARS maps to exactly one node there.
-- 2. ARS 00000 marks a stop without an ARS number (depots and other virtual
--    stops). The same-ARS fill treated it as one stop and copied one code
--    (277102436) onto about ten different depots. A code is kept only where the
--    stop name matches one 00000 stop in bus_stop_location; otherwise it is cleared.
--
-- Affected rows are copied to analysis_table_final_bad_stdcode_ext_20261105 first.
-- Re-running stops at CREATE TABLE. Run with: psql ... -v ON_ERROR_STOP=1 -f <this file>
SET client_encoding = 'UTF8';

BEGIN;

CREATE TEMP TABLE seoul AS
  SELECT DISTINCT 정류장번호 AS ars FROM bus_stop_location;

CREATE TEMP TABLE ext AS
  SELECT ars_id AS ars, MIN(canonical_node_id) AS node
  FROM integrated_bus_stop_location
  WHERE ars_id IS NOT NULL AND ars_id NOT IN (SELECT ars FROM seoul)
  GROUP BY 1 HAVING COUNT(DISTINCT canonical_node_id) = 1;

CREATE TEMP TABLE virt AS
  SELECT 정류장명 AS name, MIN(노드id) AS node
  FROM bus_stop_location WHERE 정류장번호 = '00000'
  GROUP BY 1 HAVING COUNT(DISTINCT 노드id) = 1;

ANALYZE seoul; ANALYZE ext; ANALYZE virt;

\echo '--- backup'
CREATE TABLE analysis_table_final_bad_stdcode_ext_20261105 AS
SELECT a.* FROM analysis_table_final a
LEFT JOIN ext e1 ON e1.ars = a.승차_정류장ars
LEFT JOIN ext e2 ON e2.ars = a.하차_정류장ars
LEFT JOIN virt v1 ON a.승차_정류장ars = '00000' AND v1.name = a.승차_정류장명
LEFT JOIN virt v2 ON a.하차_정류장ars = '00000' AND v2.name = a.하차_정류장명
WHERE (e1.ars IS NOT NULL AND e1.node IS DISTINCT FROM a.승차_정류장표준코드)
   OR (e2.ars IS NOT NULL AND e2.node IS DISTINCT FROM a.하차_정류장표준코드)
   OR (a.승차_정류장ars = '00000' AND v1.node IS DISTINCT FROM a.승차_정류장표준코드)
   OR (a.하차_정류장ars = '00000' AND v2.node IS DISTINCT FROM a.하차_정류장표준코드);

\echo '--- outside Seoul: boarding, then alighting'
UPDATE analysis_table_final a SET 승차_정류장표준코드 = e.node
FROM ext e WHERE e.ars = a.승차_정류장ars AND e.node IS DISTINCT FROM a.승차_정류장표준코드;

UPDATE analysis_table_final a SET 하차_정류장표준코드 = e.node
FROM ext e WHERE e.ars = a.하차_정류장ars AND e.node IS DISTINCT FROM a.하차_정류장표준코드;

\echo '--- ARS 00000 matched by name: boarding, then alighting'
UPDATE analysis_table_final a SET 승차_정류장표준코드 = v.node
FROM virt v WHERE a.승차_정류장ars = '00000' AND v.name = a.승차_정류장명
  AND v.node IS DISTINCT FROM a.승차_정류장표준코드;

UPDATE analysis_table_final a SET 하차_정류장표준코드 = v.node
FROM virt v WHERE a.하차_정류장ars = '00000' AND v.name = a.하차_정류장명
  AND v.node IS DISTINCT FROM a.하차_정류장표준코드;

\echo '--- ARS 00000 with no unique name match, cleared: boarding, then alighting'
UPDATE analysis_table_final a SET 승차_정류장표준코드 = NULL
WHERE a.승차_정류장ars = '00000' AND a.승차_정류장표준코드 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM virt v WHERE v.name = a.승차_정류장명);

UPDATE analysis_table_final a SET 하차_정류장표준코드 = NULL
WHERE a.하차_정류장ars = '00000' AND a.하차_정류장표준코드 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM virt v WHERE v.name = a.하차_정류장명);

\echo '--- remaining disagreements outside Seoul (must be 0, 0) and 277102436 left on ARS 00000'
SELECT
  (SELECT COUNT(*) FROM analysis_table_final a JOIN ext e ON e.ars = a.승차_정류장ars
     WHERE e.node IS DISTINCT FROM a.승차_정류장표준코드) AS ext_boarding,
  (SELECT COUNT(*) FROM analysis_table_final a JOIN ext e ON e.ars = a.하차_정류장ars
     WHERE e.node IS DISTINCT FROM a.하차_정류장표준코드) AS ext_alighting,
  (SELECT COUNT(*) FROM analysis_table_final
     WHERE (승차_정류장ars = '00000' AND 승차_정류장표준코드 = '277102436')
        OR (하차_정류장ars = '00000' AND 하차_정류장표준코드 = '277102436')) AS virt_277102436;

COMMIT;
