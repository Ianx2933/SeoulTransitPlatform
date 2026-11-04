-- Applied to the local database and Cloud SQL on 2026-11-04, after file 01.
-- Result in both: backup 117,110 rows; 71,071 boarding and 85,323 alighting
-- codes rewritten; 0, 0 remaining. Re-running stops at CREATE TABLE.
-- Rewrites standard codes that disagree with the stop master for the row's ARS.
-- Only ARS with exactly one node in bus_stop_location are touched.
-- Mismatched rows are copied to analysis_table_final_bad_stdcode_seq_20261104 first.
-- Run with: psql ... -v ON_ERROR_STOP=1 -f fix_stdcode_seq.sql
SET client_encoding = 'UTF8';

BEGIN;

CREATE TEMP TABLE m AS
  SELECT 정류장번호 AS ars, MIN(노드id) AS node
  FROM bus_stop_location GROUP BY 1 HAVING COUNT(DISTINCT 노드id) = 1;
ANALYZE m;

\echo '--- backup'
CREATE TABLE analysis_table_final_bad_stdcode_seq_20261104 AS
SELECT a.* FROM analysis_table_final a
LEFT JOIN m m1 ON m1.ars = a.승차_정류장ars
LEFT JOIN m m2 ON m2.ars = a.하차_정류장ars
WHERE (m1.ars IS NOT NULL AND m1.node IS DISTINCT FROM a.승차_정류장표준코드)
   OR (m2.ars IS NOT NULL AND m2.node IS DISTINCT FROM a.하차_정류장표준코드);

\echo '--- boarding, then alighting'
UPDATE analysis_table_final a SET 승차_정류장표준코드 = m.node
FROM m WHERE m.ars = a.승차_정류장ars AND m.node IS DISTINCT FROM a.승차_정류장표준코드;

UPDATE analysis_table_final a SET 하차_정류장표준코드 = m.node
FROM m WHERE m.ars = a.하차_정류장ars AND m.node IS DISTINCT FROM a.하차_정류장표준코드;

\echo '--- remaining disagreements (must be 0, 0)'
SELECT
  COUNT(*) FILTER (WHERE m1.node IS DISTINCT FROM a.승차_정류장표준코드 AND m1.ars IS NOT NULL) AS boarding,
  COUNT(*) FILTER (WHERE m2.node IS DISTINCT FROM a.하차_정류장표준코드 AND m2.ars IS NOT NULL) AS alighting
FROM analysis_table_final a
LEFT JOIN m m1 ON m1.ars = a.승차_정류장ars
LEFT JOIN m m2 ON m2.ars = a.하차_정류장ars;

\echo '--- rows that cannot be checked (ARS not in master or ambiguous), by date'
SELECT a.기준일자,
  COUNT(*) FILTER (WHERE m1.ars IS NULL) AS boarding_unchecked,
  COUNT(*) FILTER (WHERE m2.ars IS NULL) AS alighting_unchecked,
  COUNT(*) AS total
FROM analysis_table_final a
LEFT JOIN m m1 ON m1.ars = a.승차_정류장ars
LEFT JOIN m m2 ON m2.ars = a.하차_정류장ars
GROUP BY 1 ORDER BY 1;

COMMIT;
