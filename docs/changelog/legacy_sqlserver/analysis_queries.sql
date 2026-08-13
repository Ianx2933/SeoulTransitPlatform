-- LEGACY - SQL Server era. Not used by the current schema.
-- (레거시 - SQL Server 시절. 현재 스키마에서 사용하지 않습니다.)
--
-- T-SQL syntax (SELECT TOP, DATEPART) does not run on PostgreSQL, and the
-- tables referenced here (트랜잭션테이블, 노선테이블, 정류장테이블) belong to the
-- original raw data structure, which no longer exists.
--
-- Kept as a record of the analysis that produced the findings in
-- docs/analysis/policy_insights.md.
-- (docs/analysis/policy_insights.md의 분석 결과를 낸 쿼리 기록입니다.)

-- ================================================
-- 서울 버스 OD 데이터 분석 쿼리 모음
-- 작성자: Ian Lee
-- 작성일: 2026-03-29
-- ================================================

-- ────────────────────────────────────────────────
-- [1] 기본 현황 파악
-- ────────────────────────────────────────────────

-- 1-1. 전체 데이터 건수 확인
SELECT COUNT(*) AS 전체건수
FROM 트랜잭션테이블;

-- 1-2. 노선별 이용객 수
SELECT 
    노선명칭,
    SUM(이용자수) AS 총이용자수,
    COUNT(*) AS 트랜잭션수
FROM 트랜잭션테이블 t
JOIN 노선테이블 r ON t.정산사노선ID = r.노선ID
GROUP BY 노선명칭
ORDER BY 총이용자수 DESC;

-- ────────────────────────────────────────────────
-- [2] 시간대별 분석
-- ────────────────────────────────────────────────

-- 2-1. 시간대별 승차 이용객 수
SELECT 
    DATEPART(HOUR, 승차일시) AS 승차시간대,
    SUM(이용자수) AS 이용자수,
    COUNT(*) AS 트랜잭션수
FROM 트랜잭션테이블
GROUP BY DATEPART(HOUR, 승차일시)
ORDER BY 승차시간대;

-- 2-2. 노선별 + 시간대별 이용객 수
SELECT 
    r.노선명칭,
    DATEPART(HOUR, t.승차일시) AS 승차시간대,
    SUM(t.이용자수) AS 이용자수
FROM 트랜잭션테이블 t
JOIN 노선테이블 r ON t.정산사노선ID = r.노선ID
GROUP BY r.노선명칭, DATEPART(HOUR, t.승차일시)
ORDER BY r.노선명칭, 승차시간대;

-- 2-3. 출퇴근 시간대 집중도 (7~9시, 18~20시)
SELECT
    r.노선명칭,
    SUM(CASE WHEN DATEPART(HOUR, t.승차일시) BETWEEN 7 AND 9 
        THEN t.이용자수 ELSE 0 END) AS 출근시간대,
    SUM(CASE WHEN DATEPART(HOUR, t.승차일시) BETWEEN 18 AND 20 
        THEN t.이용자수 ELSE 0 END) AS 퇴근시간대,
    SUM(t.이용자수) AS 전체이용자수,
    ROUND(SUM(CASE WHEN DATEPART(HOUR, t.승차일시) BETWEEN 7 AND 9 
        THEN t.이용자수 ELSE 0 END) * 100.0 / SUM(t.이용자수), 1) AS 출근비율,
    ROUND(SUM(CASE WHEN DATEPART(HOUR, t.승차일시) BETWEEN 18 AND 20 
        THEN t.이용자수 ELSE 0 END) * 100.0 / SUM(t.이용자수), 1) AS 퇴근비율
FROM 트랜잭션테이블 t
JOIN 노선테이블 r ON t.정산사노선ID = r.노선ID
GROUP BY r.노선명칭
ORDER BY 전체이용자수 DESC;

-- ────────────────────────────────────────────────
-- [3] 구간별 수요 분석
-- ────────────────────────────────────────────────

-- 3-1. 정류장별 승차 수요 TOP 20
SELECT TOP 20
    s.정류장명칭,
    s.노선명칭,
    SUM(t.이용자수) AS 승차이용자수
FROM 트랜잭션테이블 t
JOIN 정류장테이블 s ON t.정산사승차정류장ID = s.정류장ID
GROUP BY s.정류장명칭, s.노선명칭
ORDER BY 승차이용자수 DESC;

-- 3-2. 정류장별 하차 수요 TOP 20
SELECT TOP 20
    s.정류장명칭,
    s.노선명칭,
    SUM(t.이용자수) AS 하차이용자수
FROM 트랜잭션테이블 t
JOIN 정류장테이블 s ON t.정산사하차정류장ID = s.정류장ID
GROUP BY s.정류장명칭, s.노선명칭
ORDER BY 하차이용자수 DESC;

-- 3-3. 특정 구간 수요 (승차정류장 순번 기준)
-- 예시: 302번 상대원~잠실역 구간
SELECT
    s1.정류장명칭 AS 승차정류장,
    s2.정류장명칭 AS 하차정류장,
    SUM(t.이용자수) AS 이용자수
FROM 트랜잭션테이블 t
JOIN 정류장테이블 s1 ON t.정산사승차정류장ID = s1.정류장ID
JOIN 정류장테이블 s2 ON t.정산사하차정류장ID = s2.정류장ID
WHERE s1.노선명칭 = '302'
AND s1.정류장순번 BETWEEN 1 AND 41
AND s2.정류장순번 BETWEEN 1 AND 41
GROUP BY s1.정류장명칭, s2.정류장명칭
ORDER BY 이용자수 DESC;

-- ────────────────────────────────────────────────
-- [4] OD 패턴 분석
-- ────────────────────────────────────────────────

-- 4-1. 노선별 주요 OD 쌍 TOP 20
SELECT TOP 20
    s1.노선명칭,
    s1.정류장명칭 AS 승차정류장,
    s2.정류장명칭 AS 하차정류장,
    SUM(t.이용자수) AS 이용자수
FROM 트랜잭션테이블 t
JOIN 정류장테이블 s1 ON t.정산사승차정류장ID = s1.정류장ID
JOIN 정류장테이블 s2 ON t.정산사하차정류장ID = s2.정류장ID
GROUP BY s1.노선명칭, s1.정류장명칭, s2.정류장명칭
ORDER BY 이용자수 DESC;

-- 4-2. 환승 패턴 분석
SELECT
    환승건수,
    SUM(이용자수) AS 이용자수,
    ROUND(SUM(이용자수) * 100.0 / SUM(SUM(이용자수)) OVER(), 1) AS 비율
FROM 트랜잭션테이블
GROUP BY 환승건수
ORDER BY 환승건수;

-- 4-3. 이용자 유형별 분석
SELECT
    이용자유형코드,
    SUM(이용자수) AS 이용자수,
    ROUND(SUM(이용자수) * 100.0 / SUM(SUM(이용자수)) OVER(), 1) AS 비율
FROM 트랜잭션테이블
GROUP BY 이용자유형코드
ORDER BY 이용자수 DESC;

-- ────────────────────────────────────────────────
-- [5] 이용거리 / 탑승시간 분석
-- ────────────────────────────────────────────────

-- 5-1. 노선별 평균 이용거리 및 탑승시간
SELECT
    r.노선명칭,
    ROUND(AVG(CAST(t.이용거리 AS FLOAT)), 1) AS 평균이용거리,
    ROUND(AVG(CAST(t.탑승시간 AS FLOAT)), 1) AS 평균탑승시간,
    SUM(t.이용자수) AS 총이용자수
FROM 트랜잭션테이블 t
JOIN 노선테이블 r ON t.정산사노선ID = r.노선ID
GROUP BY r.노선명칭
ORDER BY 총이용자수 DESC;

-- 5-2. 이용거리 구간별 분포
SELECT
    CASE 
        WHEN 이용거리 < 3000 THEN '3km 미만'
        WHEN 이용거리 < 5000 THEN '3~5km'
        WHEN 이용거리 < 10000 THEN '5~10km'
        WHEN 이용거리 < 20000 THEN '10~20km'
        ELSE '20km 이상'
    END AS 이용거리구간,
    SUM(이용자수) AS 이용자수,
    ROUND(SUM(이용자수) * 100.0 / SUM(SUM(이용자수)) OVER(), 1) AS 비율
FROM 트랜잭션테이블
GROUP BY
    CASE 
        WHEN 이용거리 < 3000 THEN '3km 미만'
        WHEN 이용거리 < 5000 THEN '3~5km'
        WHEN 이용거리 < 10000 THEN '5~10km'
        WHEN 이용거리 < 20000 THEN '10~20km'
        ELSE '20km 이상'
    END
ORDER BY 이용자수 DESC;