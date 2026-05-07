-- Subway station join alias table
-- (지하철 역사 조인 alias 테이블)

CREATE TABLE IF NOT EXISTS subway_station_join_alias (
    source_line_name VARCHAR(100),
    source_station_name VARCHAR(100),
    target_line_name VARCHAR(100),
    target_station_name VARCHAR(100),
    reason VARCHAR(255),
    PRIMARY KEY (source_line_name, source_station_name)
);

-- Current service-name normalization
-- (현재 서비스 기준 역명 정규화)
UPDATE subway_station_location
SET station_name = '자양'
WHERE station_name = '뚝섬유원지'
  AND line_name = '7호선';

-- Alias records for line/station correction
-- (노선/역명 보정을 위한 alias 데이터)
INSERT INTO subway_station_join_alias
(source_line_name, source_station_name, target_line_name, target_station_name, reason)
VALUES
('9호선2~3단계', '*', '9호선(연장)', '*', 'line name normalization'),
('경의선', '*', '경의중앙선', '*', 'line name normalization'),
('공항철도 1호선', '*', '공항철도1호선', '*', 'line name normalization'),
('분당선', '복정', '8호선', '복정', 'shared gate / use 8호선 coordinate'),
('경원선', '창동', '4호선', '창동', 'shared gate / use 4호선 coordinate'),
('경부선', '평택지제', '경부선', '지제', 'station renamed'),
('경의선', '검암', '공항철도1호선', '검암', 'wrong source line / use airport railroad coordinate'),
('경의선', '계양', '공항철도1호선', '계양', 'wrong source line / use airport railroad coordinate'),
('경의선', '김포공항', '공항철도1호선', '김포공항', 'wrong source line / use airport railroad coordinate'),
('경의선', '한국항공대', '경의중앙선', '화전', 'station renamed from 화전 to 한국항공대')
ON CONFLICT (source_line_name, source_station_name)
DO UPDATE SET
    target_line_name = EXCLUDED.target_line_name,
    target_station_name = EXCLUDED.target_station_name,
    reason = EXCLUDED.reason;
