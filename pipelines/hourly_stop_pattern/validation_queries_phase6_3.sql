-- 1. Row counts
SELECT COUNT(*) AS pattern_rows
FROM dow_hourly_stop_pattern;

SELECT COUNT(*) AS demand_rows
FROM estimated_hourly_stop_demand;

-- 2. Ratio-sum validation.
-- Valid non-zero groups should be close to 1.
SELECT route_no, stop_ars, day_type, SUM(boarding_ratio) AS boarding_total_ratio
FROM dow_hourly_stop_pattern
GROUP BY route_no, stop_ars, day_type
HAVING SUM(boarding_ratio) > 0
   AND ABS(SUM(boarding_ratio) - 1) > 0.01;

SELECT route_no, stop_ars, day_type, SUM(alighting_ratio) AS alighting_total_ratio
FROM dow_hourly_stop_pattern
GROUP BY route_no, stop_ars, day_type
HAVING SUM(alighting_ratio) > 0
   AND ABS(SUM(alighting_ratio) - 1) > 0.01;

-- 3. Zero-ratio groups.
-- These are fallback or sparse-data candidates.
SELECT COUNT(*) AS zero_boarding_groups
FROM (
    SELECT route_no, stop_ars, day_type, SUM(boarding_ratio) AS total_ratio
    FROM dow_hourly_stop_pattern
    GROUP BY route_no, stop_ars, day_type
    HAVING SUM(boarding_ratio) = 0
) t;

SELECT COUNT(*) AS zero_alighting_groups
FROM (
    SELECT route_no, stop_ars, day_type, SUM(alighting_ratio) AS total_ratio
    FROM dow_hourly_stop_pattern
    GROUP BY route_no, stop_ars, day_type
    HAVING SUM(alighting_ratio) = 0
) t;

-- 4. Sample pattern inspection
SELECT *
FROM dow_hourly_stop_pattern
WHERE route_no = '01A'
ORDER BY stop_ars, day_type, hour
LIMIT 100;
