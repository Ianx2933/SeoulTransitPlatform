-- Java odcorrection, statement for statement, against session temp tables.
--
-- Source: services/api-server/src/main/java/com/ian/transit/odcorrection/
--   OdCorrectionOrchestrator: fixVirtualStop -> fixSequence -> fixSameStopOD,
--   each followed by deduplicateAndSum.
--
-- This file is the reference the pandas port (od_structural_correction.py,
-- mode=faithful) is compared against row for row. It is run by
-- run_java_baseline.py, once per 기준일자, with :'basedate' set.
--
-- Changes from the Java SQL, and why:
--   * analysis_table_final -> pg_temp.od_work, anomaly_data -> pg_temp.od_anomaly,
--     중복합산임시 -> pg_temp.중복합산임시. The runner sets search_path to pg_temp
--     only, so no statement here can reach a real table.
--   * JPQL statements are written as the SQL Hibernate generates for them.
--   * D1 fixSequenceByArs: ROW_NUMBER() ... ORDER BY COUNT(*) DESC has no tie
--     break, so PostgreSQL picks any of the tied sequences. Added
--     ", 승차_정류장순번 ASC NULLS LAST" so the result is reproducible.
--   * D2 fixSameStopODByArs: UPDATE ... FROM analysis_table_final b can match
--     several b rows (different stops that share a sequence number), and
--     PostgreSQL then uses an arbitrary one. b is replaced by one row per
--     (기준일자, 노선명, 승차_정류장순번): the smallest (ARS, code, name) in byte
--     order (COLLATE "C"), NULLs last.
-- Nothing else is changed, including the deduplicateAndSum defects the port
-- fixes in mode=fixed.

\echo STEP fixVirtualStopBoardingArs
UPDATE pg_temp.od_work b SET 승차_정류장ars = '00000'
 WHERE b.기준일자 = :'basedate'
   AND (b.승차_정류장명 LIKE '%가상%' OR b.승차_정류장명 LIKE '%기점가상%' OR b.승차_정류장명 LIKE '%경유%')
   AND (b.승차_정류장ars <> '06137' OR b.승차_정류장ars IS NULL);

\echo STEP fixVirtualStopAlightingArs
UPDATE pg_temp.od_work b SET 하차_정류장ars = '00000'
 WHERE b.기준일자 = :'basedate'
   AND (b.하차_정류장명 LIKE '%가상%' OR b.하차_정류장명 LIKE '%기점가상%' OR b.하차_정류장명 LIKE '%경유%')
   AND (b.하차_정류장ars <> '06137' OR b.하차_정류장ars IS NULL);

\echo STEP fixBoardingSequence
UPDATE pg_temp.od_work SET 승차_정류장순번 = 0
 WHERE 기준일자 = :'basedate' AND 승차_정류장ars = '00000' AND 승차_정류장순번 IS NULL;

\echo STEP fixAlightingSequenceCaseA
UPDATE pg_temp.od_work SET 하차_정류장순번 = 0
 WHERE 기준일자 = :'basedate' AND 승차_정류장순번 = 0 AND 승차_정류장ars = '00000'
   AND 하차_정류장ars = '00000' AND 하차_정류장순번 IS NULL;

\echo STEP fixAlightingSequenceCaseC1
UPDATE pg_temp.od_work SET 하차_정류장명 = 승차_정류장명, 하차_정류장ars = 승차_정류장ars,
       하차_정류장표준코드 = 승차_정류장표준코드, 하차_정류장순번 = 승차_정류장순번
 WHERE 기준일자 = :'basedate' AND 하차_정류장ars = '00000' AND 하차_정류장순번 IS NULL
   AND 승차_정류장순번 BETWEEN 1 AND 10;

\echo STEP insertAnomalyDataCaseC2
INSERT INTO pg_temp.od_anomaly
SELECT a.기준일자, a.노선명, a.전환_노선id, a.승차_정류장순번, a.승차_정류장ars, a.승차_정류장표준코드,
       a.승차_정류장명, a.하차_정류장순번, a.하차_정류장ars, a.하차_정류장표준코드, a.하차_정류장명, a.승객수
  FROM pg_temp.od_work a
 INNER JOIN (SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 FROM pg_temp.od_work
              WHERE 승차_정류장순번 IS NOT NULL AND 기준일자 = :'basedate' GROUP BY 노선명) b
    ON a.노선명 = b.노선명
 WHERE a.기준일자 = :'basedate' AND a.하차_정류장ars = '00000' AND a.하차_정류장순번 IS NULL
   AND a.승차_정류장순번::FLOAT / b.최대순번 * 100 < 48 AND a.승차_정류장순번 >= 11;

\echo STEP deleteAnomalyDataCaseC2
DELETE FROM pg_temp.od_work a
 USING (SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 FROM pg_temp.od_work
         WHERE 승차_정류장순번 IS NOT NULL AND 기준일자 = :'basedate' GROUP BY 노선명) b
 WHERE a.노선명 = b.노선명 AND a.기준일자 = :'basedate' AND a.하차_정류장ars = '00000'
   AND a.하차_정류장순번 IS NULL AND a.승차_정류장순번::FLOAT / b.최대순번 * 100 < 48
   AND a.승차_정류장순번 >= 11;

\echo STEP fixAlightingSequenceCaseB
UPDATE pg_temp.od_work a SET 하차_정류장순번 = b.최대순번 + 1
  FROM (SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 FROM pg_temp.od_work
         WHERE 승차_정류장순번 IS NOT NULL AND 기준일자 = :'basedate' GROUP BY 노선명) b
 WHERE a.노선명 = b.노선명 AND a.기준일자 = :'basedate' AND a.하차_정류장ars = '00000'
   AND a.하차_정류장순번 IS NULL;

\echo STEP fixEmptyArrivalStopName
UPDATE pg_temp.od_work b SET 하차_정류장명 = b.승차_정류장명
 WHERE b.기준일자 = :'basedate' AND (b.하차_정류장명 IS NULL OR b.하차_정류장명 = '')
   AND b.하차_정류장표준코드 = '277102436';

\echo STEP fixVirtualStopBoarding
UPDATE pg_temp.od_work SET 하차_정류장순번 = 승차_정류장순번, 하차_정류장ars = 승차_정류장ars,
       하차_정류장표준코드 = 승차_정류장표준코드, 하차_정류장명 = 승차_정류장명
 WHERE 기준일자 = :'basedate' AND 승차_정류장ars <> '00000' AND 하차_정류장ars = '00000'
   AND 하차_정류장순번 IS NOT NULL;

\echo STEP fixMidVirtualStopBoarding
UPDATE pg_temp.od_work SET 승차_정류장순번 = 하차_정류장순번, 승차_정류장ars = 하차_정류장ars,
       승차_정류장표준코드 = 하차_정류장표준코드, 승차_정류장명 = 하차_정류장명
 WHERE 기준일자 = :'basedate' AND 승차_정류장ars = '00000' AND 승차_정류장순번 <> 0;

\echo STEP fixVirtualStopSameOD
UPDATE pg_temp.od_work SET 하차_정류장순번 = 승차_정류장순번 + 1
 WHERE 기준일자 = :'basedate' AND 승차_정류장ars = '00000' AND 하차_정류장ars = '00000'
   AND 승차_정류장순번 = 하차_정류장순번;

\echo STEP dedup.virtual
\ir java_dedup.sql

\echo STEP fixSequenceByArs
UPDATE pg_temp.od_work a SET 승차_정류장순번 = b.최빈순번
  FROM (SELECT 노선명, 승차_정류장ars, 승차_정류장순번 AS 최빈순번
          FROM (SELECT 노선명, 승차_정류장ars, 승차_정류장순번,
                       ROW_NUMBER() OVER (PARTITION BY 노선명, 승차_정류장ars
                                          ORDER BY COUNT(*) DESC, 승차_정류장순번 ASC NULLS LAST) AS rn  -- D1
                  FROM pg_temp.od_work
                 WHERE 승차_정류장ars <> '00000' AND 기준일자 = :'basedate'
                 GROUP BY 노선명, 승차_정류장ars, 승차_정류장순번) ranked
         WHERE rn = 1) b
 WHERE a.노선명 = b.노선명 AND a.승차_정류장ars = b.승차_정류장ars
   AND a.승차_정류장순번 <> b.최빈순번 AND a.기준일자 = :'basedate';

\echo STEP fixAlightingSequenceByArs
UPDATE pg_temp.od_work a SET 하차_정류장순번 = b.최소하차순번
  FROM (SELECT 노선명, 승차_정류장순번, 승차_정류장ars, 하차_정류장ars, MIN(하차_정류장순번) AS 최소하차순번
          FROM pg_temp.od_work
         WHERE 승차_정류장ars <> '00000' AND 하차_정류장ars <> '00000' AND 기준일자 = :'basedate'
         GROUP BY 노선명, 승차_정류장순번, 승차_정류장ars, 하차_정류장ars
        HAVING COUNT(DISTINCT 하차_정류장순번) > 1
           AND MAX(하차_정류장순번) - MIN(하차_정류장순번) <= 2) b
 WHERE a.노선명 = b.노선명 AND a.승차_정류장순번 = b.승차_정류장순번
   AND a.승차_정류장ars = b.승차_정류장ars AND a.하차_정류장ars = b.하차_정류장ars
   AND a.하차_정류장순번 <> b.최소하차순번 AND a.기준일자 = :'basedate';

\echo STEP dedup.sequence
\ir java_dedup.sql

\echo STEP fixSameStopODBySequence
UPDATE pg_temp.od_work SET 하차_정류장순번 = 승차_정류장순번 + 1
 WHERE 기준일자 = :'basedate' AND 승차_정류장순번 = 하차_정류장순번
   AND 승차_정류장ars = 하차_정류장ars AND 승차_정류장ars <> '00000';

\echo STEP fixSameStopODByArs
UPDATE pg_temp.od_work a
   SET 하차_정류장순번 = a.승차_정류장순번 + 1, 하차_정류장ars = b.승차_정류장ars,
       하차_정류장표준코드 = b.승차_정류장표준코드, 하차_정류장명 = b.승차_정류장명
  FROM (SELECT DISTINCT ON (기준일자, 노선명, 승차_정류장순번)                                   -- D2
               기준일자, 노선명, 승차_정류장순번, 승차_정류장ars, 승차_정류장표준코드, 승차_정류장명
          FROM pg_temp.od_work
         ORDER BY 기준일자, 노선명, 승차_정류장순번,
                  승차_정류장ars COLLATE "C", 승차_정류장표준코드 COLLATE "C", 승차_정류장명 COLLATE "C") b
 WHERE a.노선명 = b.노선명 AND a.기준일자 = b.기준일자
   AND b.승차_정류장순번 = a.승차_정류장순번 + 1
   AND a.승차_정류장ars = a.하차_정류장ars AND a.기준일자 = :'basedate';

\echo STEP dedup.samestop
\ir java_dedup.sql
