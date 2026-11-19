-- 행정동·구 "도로 위 수요"(GET /api/dong-od/{code}/route-load)가 느린 단계 찾기.
-- RouteLoadQueryRepository의 SQL 세 개(레그 / 노선 정류소 / 구간)를 앱과 같은 모양으로
-- EXPLAIN (ANALYZE, BUFFERS) 하고, 인덱스·표 크기·응답 크기를 같이 뽑는다.
-- 읽기만 한다(임시 테이블은 이 세션에만 있고 끝나면 사라짐).
--
-- 변수 (psql -v):
--   code     구역 시작 코드. 동: 11010530(사직동), 구: 11010(종로구)
--   code_end 다음 코드.      동: 11010531,          구: 11011
--   label    od_typical_route_stop.기준일자 (기본 평일: typical_weekday)
-- 앱은 동이면 adm_cd = :code, 구면 adm_cd >= :code AND adm_cd < :codeEnd 를 쓴다.
-- 동 코드는 모두 8자리라 범위 [11010530, 11010531)는 = 과 같은 행을 고른다.

\set ON_ERROR_STOP on
\pset pager off
-- EXPLAIN ANALYZE가 단계마다 Execution Time을 찍으므로 \timing은 안 씀(-o 파일에 안 남음).
SET statement_timeout = '120s';

\qecho '=== 0. 인덱스 ==='
SELECT tablename, indexname, indexdef
FROM pg_indexes
WHERE tablename IN ('od_typical_route_stop', 'bus_stop_location', 'integrated_bus_stop_location',
                    'stop_location_fix', 'admin_dong_boundary', 'bus_route_path_route',
                    'bus_route_path_station', 'bus_route_path_segment')
ORDER BY 1, 2;

\qecho '=== 0. 표 크기 ==='
SELECT c.relname, c.reltuples::bigint AS rows_est,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total
FROM pg_class c
WHERE c.relkind = 'r'
  AND c.relname IN ('od_typical_route_stop', 'bus_stop_location', 'integrated_bus_stop_location',
                    'stop_location_fix', 'admin_dong_boundary', 'bus_route_path_route',
                    'bus_route_path_station', 'bus_route_path_segment')
ORDER BY 1;

\qecho '=== 0. 기준일자 값 (label 확인) ==='
SELECT 기준일자, COUNT(*) FROM od_typical_route_stop GROUP BY 1 ORDER BY 1;

\qecho '=== 1a. 구역 정류소만 (AREA_STOPS) ==='
EXPLAIN (ANALYZE, BUFFERS)
WITH dong AS (
    SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary
    WHERE adm_cd >= :'code' AND adm_cd < :'code_end'
),
stops AS MATERIALIZED (
    SELECT LPAD("정류장번호"::text, 5, '0') AS ars
    FROM bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)))
      AND LPAD("정류장번호"::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT LPAD(b.ars_id::text, 5, '0')
    FROM integrated_bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)))
      AND LPAD(b.ars_id::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT f.ars
    FROM stop_location_fix f, dong
    WHERE ST_Covers(dong.g, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326))
),
stop_keys AS MATERIALIZED (
    SELECT ars FROM stops WHERE ars <> '00000'
    UNION
    SELECT LTRIM(ars, '0') FROM stops WHERE ars <> '00000'
)
SELECT COUNT(*) FROM stop_keys;

\qecho '=== 1b. 레그 전체 (direction=both, 앱 그대로) ==='
EXPLAIN (ANALYZE, BUFFERS)
WITH dong AS (
    SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary
    WHERE adm_cd >= :'code' AND adm_cd < :'code_end'
),
stops AS MATERIALIZED (
    SELECT LPAD("정류장번호"::text, 5, '0') AS ars
    FROM bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)))
      AND LPAD("정류장번호"::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT LPAD(b.ars_id::text, 5, '0')
    FROM integrated_bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)))
      AND LPAD(b.ars_id::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT f.ars
    FROM stop_location_fix f, dong
    WHERE ST_Covers(dong.g, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326))
),
stop_keys AS MATERIALIZED (
    SELECT ars FROM stops WHERE ars <> '00000'
    UNION
    SELECT LTRIM(ars, '0') FROM stops WHERE ars <> '00000'
)
SELECT 노선명 AS route, 승차_정류장ars AS b, 하차_정류장ars AS a,
       승차_정류장순번 AS bq, 하차_정류장순번 AS aq, SUM(승객수) AS p
FROM od_typical_route_stop
WHERE 기준일자 = :'label' AND 승차_정류장ars IN (SELECT ars FROM stop_keys)
GROUP BY 1, 2, 3, 4, 5
UNION
SELECT 노선명 AS route, 승차_정류장ars AS b, 하차_정류장ars AS a,
       승차_정류장순번 AS bq, 하차_정류장순번 AS aq, SUM(승객수) AS p
FROM od_typical_route_stop
WHERE 기준일자 = :'label' AND 하차_정류장ars IN (SELECT ars FROM stop_keys)
GROUP BY 1, 2, 3, 4, 5;

-- 다음 두 단계에 넘길 노선 목록을 만든다(레그를 한 번 더 돌림, 임시 테이블).
CREATE TEMP TABLE t_legs AS
WITH dong AS (
    SELECT ST_Union(geom) AS g FROM public.admin_dong_boundary
    WHERE adm_cd >= :'code' AND adm_cd < :'code_end'
),
stops AS MATERIALIZED (
    SELECT LPAD("정류장번호"::text, 5, '0') AS ars
    FROM bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)))
      AND LPAD("정류장번호"::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT LPAD(b.ars_id::text, 5, '0')
    FROM integrated_bus_stop_location b, dong
    WHERE ST_Covers(dong.g, COALESCE(b.geom, ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)))
      AND LPAD(b.ars_id::text, 5, '0') NOT IN (SELECT ars FROM stop_location_fix)
    UNION
    SELECT f.ars
    FROM stop_location_fix f, dong
    WHERE ST_Covers(dong.g, ST_SetSRID(ST_MakePoint(f.lng, f.lat), 4326))
),
stop_keys AS MATERIALIZED (
    SELECT ars FROM stops WHERE ars <> '00000'
    UNION
    SELECT LTRIM(ars, '0') FROM stops WHERE ars <> '00000'
)
SELECT 노선명 AS route, 승차_정류장ars AS b, 하차_정류장ars AS a, SUM(승객수) AS p
FROM od_typical_route_stop
WHERE 기준일자 = :'label'
  AND (승차_정류장ars IN (SELECT ars FROM stop_keys) OR 하차_정류장ars IN (SELECT ars FROM stop_keys))
GROUP BY 1, 2, 3;

\qecho '=== 1c. 레그 수·노선 수·승객 ==='
SELECT COUNT(*) AS legs, COUNT(DISTINCT route) AS routes, ROUND(SUM(p)::numeric, 1) AS passengers FROM t_legs;

-- 앱은 노선 이름을 IN (:routes) 목록으로 넘긴다. 같은 모양으로 만든다.
SELECT string_agg(quote_literal(route), ',') AS routes FROM (SELECT DISTINCT route FROM t_legs) x \gset

\qecho '=== 2. 노선 정류소 (STATIONS_SQL) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT r.od_route_nm, s.route_nm, s.seq, s.ars
FROM bus_route_path_station s
JOIN bus_route_path_route r ON r.route_nm = s.route_nm
WHERE r.status = 'ok' AND r.od_route_nm IN (:routes);

-- 앱은 수요가 실린 변형만 넘기지만, 여기선 그 노선의 ok 변형 전부(조금 더 많음).
SELECT string_agg(quote_literal(route_nm), ',') AS variants
FROM bus_route_path_route WHERE status = 'ok' AND od_route_nm IN (:routes) \gset

\qecho '=== 3. 구간 + 이름 + 단순화 (SEGMENTS_SQL) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT s.route_nm, s.seq, s.from_ars, s.to_ars,
       a.station_nm AS from_name, b.station_nm AS to_name,
       ST_AsText(ST_SimplifyPreserveTopology(s.geom, 0.00005)) AS wkt
FROM bus_route_path_segment s
LEFT JOIN bus_route_path_station a ON a.route_nm = s.route_nm AND a.seq = s.seq
LEFT JOIN LATERAL (
    SELECT n.station_nm
    FROM bus_route_path_station n
    WHERE n.route_nm = s.route_nm AND n.seq > s.seq
    ORDER BY n.seq
    LIMIT 1
) b ON true
WHERE s.route_nm IN (:variants);

\qecho '=== 3b. 구간 SQL에서 단순화·WKT 빼고 (조인만의 시간) ==='
EXPLAIN (ANALYZE, BUFFERS)
SELECT s.route_nm, s.seq, s.from_ars, s.to_ars,
       a.station_nm AS from_name, b.station_nm AS to_name
FROM bus_route_path_segment s
LEFT JOIN bus_route_path_station a ON a.route_nm = s.route_nm AND a.seq = s.seq
LEFT JOIN LATERAL (
    SELECT n.station_nm
    FROM bus_route_path_station n
    WHERE n.route_nm = s.route_nm AND n.seq > s.seq
    ORDER BY n.seq
    LIMIT 1
) b ON true
WHERE s.route_nm IN (:variants);

\qecho '=== 4. 응답 크기 (변형 수·구간 수·좌표 수·WKT 글자 수) ==='
SELECT COUNT(DISTINCT s.route_nm) AS variants,
       COUNT(*) AS segments,
       SUM(ST_NPoints(s.geom)) AS points_raw,
       SUM(ST_NPoints(ST_SimplifyPreserveTopology(s.geom, 0.00005))) AS points_simplified,
       SUM(LENGTH(ST_AsText(ST_SimplifyPreserveTopology(s.geom, 0.00005)))) AS wkt_chars
FROM bus_route_path_segment s
WHERE s.route_nm IN (:variants);

DROP TABLE t_legs;
