-- 승차 가상 정류장 전체 현황 (가상 + 기점가상 + 경유 포함)
SELECT 
    승차_정류장ARS,
    승차_정류장명,
    COUNT(*) AS 건수
FROM Seoul_Transit.dbo.Analysis_Table_Final
WHERE 승차_정류장명 LIKE '%가상%'
   OR 승차_정류장명 LIKE '%기점가상%'
   OR 승차_정류장명 LIKE '%경유%'
GROUP BY 승차_정류장ARS, 승차_정류장명
ORDER BY 건수 DESC;

-- 하차 가상 정류장 전체 현황
SELECT 
    하차_정류장ARS,
    하차_정류장명,
    COUNT(*) AS 건수
FROM Seoul_Transit.dbo.Analysis_Table_Final
WHERE 하차_정류장명 LIKE '%가상%'
   OR 하차_정류장명 LIKE '%기점가상%'
   OR 하차_정류장명 LIKE '%경유%'
GROUP BY 하차_정류장ARS, 하차_정류장명
ORDER BY 건수 DESC;
