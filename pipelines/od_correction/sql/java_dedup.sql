-- OdCorrectionJdbcRepository.deduplicateAndSum, unchanged apart from table names.
--
-- Known defects, reproduced on purpose (the pandas port fixes them in mode=fixed):
--   1. 승차_정류장표준코드 is neither grouped nor re-inserted: merged rows lose it.
--   2. The temp table groups by names and 하차 code too, the DELETE matches on
--      keys only: rows with the same key but another name are deleted without
--      being summed.
--   3. "=" in the DELETE never matches NULL (전환_노선id, 순번): those rows stay
--      and the summed copy is inserted as well.
--   4. The early-exit count groups by 8 columns, the merge by 9.

SELECT COUNT(*) > 0 AS has_duplicates FROM (
    SELECT 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장명
      FROM pg_temp.od_work WHERE 기준일자 = :'basedate'
     GROUP BY 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장명
    HAVING COUNT(*) > 1
) t \gset

\if :has_duplicates
DROP TABLE IF EXISTS pg_temp.중복합산임시;

CREATE TEMP TABLE 중복합산임시 AS
SELECT MIN(기준일자) AS 기준일자, 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명,
       하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, SUM(승객수) AS 승객수
  FROM pg_temp.od_work WHERE false
 GROUP BY 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명,
          하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명;

INSERT INTO pg_temp.중복합산임시
SELECT MIN(기준일자) AS 기준일자, 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명,
       하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, SUM(승객수) AS 승객수
  FROM pg_temp.od_work WHERE 기준일자 = :'basedate'
 GROUP BY 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명,
          하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명
HAVING COUNT(*) > 1;

DELETE FROM pg_temp.od_work a USING pg_temp.중복합산임시 b
 WHERE a.기준일자 = b.기준일자 AND a.노선명 = b.노선명 AND a.전환_노선id = b.전환_노선id
   AND a.승차_정류장순번 = b.승차_정류장순번 AND a.승차_정류장ars = b.승차_정류장ars
   AND a.하차_정류장순번 = b.하차_정류장순번 AND a.하차_정류장ars = b.하차_정류장ars;

INSERT INTO pg_temp.od_work
       (기준일자, 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번,
        하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, 승객수)
SELECT 기준일자, 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번,
       하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, 승객수
  FROM pg_temp.중복합산임시;

DROP TABLE IF EXISTS pg_temp.중복합산임시;
\else
\echo no duplicates
\endif
