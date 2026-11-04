-- Read-only. Run with -v d=YYYYMMDD.
-- Result on 2025-11-11: 9,567 boarding mismatches, 7,555 of them same_route.
-- Result on 2025-10-14: 30,692 mismatches, 23,156 same_route (75%).
-- Read-only. Classifies boarding rows whose standard code disagrees with the stop master.
-- same_route: the code belongs to another stop on the same route that day (offset = sequence gap)
-- same_name : the code belongs to a stop with the same name but a different ARS
-- other     : neither
SET client_encoding = 'UTF8';

WITH m AS (
  SELECT 정류장번호 AS ars, MIN(노드id) AS node
  FROM bus_stop_location GROUP BY 1 HAVING COUNT(DISTINCT 노드id) = 1
),
inv AS (
  SELECT node, MIN(ars) AS ars FROM m GROUP BY 1 HAVING COUNT(*) = 1
),
t AS (
  SELECT * FROM analysis_table_final WHERE 기준일자 = :'d'
),
stops AS (
  SELECT DISTINCT 노선명, 승차_정류장순번 AS seq, 승차_정류장ars AS ars, 승차_정류장명 AS nm FROM t
  UNION
  SELECT DISTINCT 노선명, 하차_정류장순번, 하차_정류장ars, 하차_정류장명 FROM t
),
bad AS (
  SELECT t.노선명, t.승차_정류장순번 AS seq, t.승차_정류장명 AS nm, i.ars AS true_ars
  FROM t
  JOIN m ON m.ars = t.승차_정류장ars
  LEFT JOIN inv i ON i.node = t.승차_정류장표준코드
  WHERE t.승차_정류장표준코드 <> m.node
),
cls AS (
  SELECT b.*,
    (SELECT MIN(ABS(s.seq - b.seq)) FROM stops s
      WHERE s.노선명 = b.노선명 AND s.ars = b.true_ars) AS off,
    EXISTS (SELECT 1 FROM stops s WHERE s.ars = b.true_ars AND s.nm = b.nm) AS same_nm
  FROM bad b
)
SELECT
  CASE WHEN true_ars IS NULL THEN 'code_not_in_master'
       WHEN off IS NOT NULL THEN 'same_route'
       WHEN same_nm THEN 'same_name'
       ELSE 'other' END AS kind,
  CASE WHEN off IS NULL THEN '-'
       WHEN off <= 3 THEN LPAD(off::text, 2, '0')
       WHEN off <= 10 THEN '04-10'
       ELSE '11+' END AS offset,
  COUNT(*) AS rows,
  COUNT(DISTINCT 노선명) AS routes
FROM cls GROUP BY 1, 2 ORDER BY 1, 2;
