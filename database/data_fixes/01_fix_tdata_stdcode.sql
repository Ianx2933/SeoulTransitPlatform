-- Removes the Incheon stop code (164000626) that the T-data lookup wrote into
-- rows of other stops, then refills empty codes from the stop master by ARS.
-- ARS 38624 is the stop that really owns 164000626 and is left alone.
-- Applied to the local database and Cloud SQL on 2026-11-04.
-- Expected: backup 396 rows; cleared 256 boarding, 144 alighting;
--           refilled 326 boarding, 272 alighting (includes codes that were
--           already empty because later lookups returned 404).
-- Re-running stops at CREATE TABLE because the backup table already exists.
-- Run with: psql ... -v ON_ERROR_STOP=1 -f <this file>
SET client_encoding = 'UTF8';

BEGIN;

CREATE TABLE analysis_table_final_bad_stdcode_20261104 AS
SELECT * FROM analysis_table_final
WHERE 승차_정류장표준코드 = '164000626' OR 하차_정류장표준코드 = '164000626';

UPDATE analysis_table_final SET 승차_정류장표준코드 = NULL
WHERE 승차_정류장표준코드 = '164000626' AND 승차_정류장ars <> '38624';

UPDATE analysis_table_final SET 하차_정류장표준코드 = NULL
WHERE 하차_정류장표준코드 = '164000626' AND 하차_정류장ars <> '38624';

CREATE TEMP TABLE m AS
  SELECT 정류장번호 AS ars, MIN(노드id) AS node
  FROM bus_stop_location GROUP BY 1 HAVING COUNT(DISTINCT 노드id) = 1;

UPDATE analysis_table_final a SET 승차_정류장표준코드 = m.node
FROM m WHERE a.승차_정류장표준코드 IS NULL AND m.ars = a.승차_정류장ars;

UPDATE analysis_table_final a SET 하차_정류장표준코드 = m.node
FROM m WHERE a.하차_정류장표준코드 IS NULL AND m.ars = a.하차_정류장ars;

-- Must be 0, 0.
SELECT COUNT(*) FILTER (WHERE 승차_정류장표준코드 = '164000626' AND 승차_정류장ars <> '38624') AS boarding,
       COUNT(*) FILTER (WHERE 하차_정류장표준코드 = '164000626' AND 하차_정류장ars <> '38624') AS alighting
FROM analysis_table_final;

COMMIT;
