-- Read-only. Run with -v d=YYYYMMDD.
-- Result on 2025-10-14: gap +2 on 17 routes (12,720 rows), +4 on 8 (5,132),
-- -1 on 3 (3,128). Of 766 wrong (route, position, code) keys, 701 also
-- appear on 2025-11-11, 521 on 2024-10-19, 490 on 2023-10-17, 21 nowhere.
-- Read-only. Two checks for boarding rows on date :d whose code disagrees with the stop master.
-- 1) Signed sequence gap to the stop that really owns the code, on the same route that day.
--    A gap that is always the same sign (e.g. always +2) points to a systematic numbering shift.
-- 2) Which other date had exactly this code at the same route name and sequence.
--    The date that explains most rows is the likely reference.
SET client_encoding = 'UTF8';

CREATE TEMP TABLE m AS
  SELECT 정류장번호 AS ars, MIN(노드id) AS node
  FROM bus_stop_location GROUP BY 1 HAVING COUNT(DISTINCT 노드id) = 1;

CREATE TEMP TABLE inv AS
  SELECT node, MIN(ars) AS ars FROM m GROUP BY 1 HAVING COUNT(*) = 1;

CREATE TEMP TABLE bad AS
  SELECT a.노선명, a.승차_정류장순번 AS seq, a.승차_정류장표준코드 AS code, i.ars AS true_ars
  FROM analysis_table_final a
  JOIN m ON m.ars = a.승차_정류장ars
  LEFT JOIN inv i ON i.node = a.승차_정류장표준코드
  WHERE a.기준일자 = :'d' AND a.승차_정류장표준코드 <> m.node;

CREATE TEMP TABLE stops AS
  SELECT DISTINCT 노선명, 승차_정류장순번 AS seq, 승차_정류장ars AS ars
  FROM analysis_table_final WHERE 기준일자 = :'d';

\echo '--- 1) signed gap (true stop seq - row seq), top 12'
SELECT g.gap, COUNT(*) AS rows, COUNT(DISTINCT b.노선명) AS routes
FROM bad b
CROSS JOIN LATERAL (
  SELECT s.seq - b.seq AS gap FROM stops s
  WHERE s.노선명 = b.노선명 AND s.ars = b.true_ars
  ORDER BY ABS(s.seq - b.seq) LIMIT 1
) g
GROUP BY 1 ORDER BY 2 DESC LIMIT 12;

\echo '--- 2) other dates that had the same code at the same route and sequence'
WITH other AS (
  SELECT DISTINCT 기준일자, 노선명, 승차_정류장순번 AS seq, 승차_정류장표준코드 AS code
  FROM analysis_table_final WHERE 기준일자 <> :'d'
)
SELECT COALESCE(o.기준일자, 'none') AS matched_date,
       COUNT(DISTINCT (b.노선명, b.seq, b.code)) AS keys,
       COUNT(DISTINCT b.노선명) AS routes
FROM bad b
LEFT JOIN other o ON o.노선명 = b.노선명 AND o.seq = b.seq AND o.code = b.code
GROUP BY 1 ORDER BY 2 DESC;

SELECT COUNT(DISTINCT (노선명, seq, code)) AS total_keys FROM bad;
