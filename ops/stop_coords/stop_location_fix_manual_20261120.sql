-- Manual decisions on stop_location_review_20261118.csv (same-name stops more
-- than 1 km apart). Rule: the ARS's first two digits name the Seoul gu
-- (05 광진, 07 중랑, 08 성북, 14 마포, 19 영등포, 21 관악), and the stop name
-- often names its dong. Where the stop table (used) is wrong, the route path
-- point goes in here; where it is right, nothing is needed.
--
--   ars    name                      used (stop table)  path          decision
--   14716  사천고가앞                 묵2동 (중랑)       연남동 (마포)  path: 14 = 마포
--   05229  건대입구역사거리.건대병원  구의2동            화양동         path: 건대입구역·건대병원 are in 화양동
--   49061  보통골동양공업사           성남동             상대원1동      path: 보통골 is in 상대원동
--   08598  고명중고교                 돈암1동 (성북)     송산3동        keep used: 08 = 성북
--   07501  중화동종점                 중화1동 (중랑)     일원1동        keep used: 07 = 중랑, name
--   07502  진주빌라.바다약국          면목3·8동 (중랑)   수서동         keep used: 07 = 중랑
--   08535  삼선시장                   삼선동 (성북)      상계8동        keep used: 08 = 성북, name
--   08538  현대홈마트                 종암동 (성북)      상계5동        keep used: 08 = 성북
--   19701  푸른숲아파트               신길1동 (영등포)   성산1동        keep used: 19 = 영등포
--   19702  문래자이정문               문래동 (영등포)    성산2동        keep used: 19 = 영등포, name
--   35278  고촌역                     고촌읍 (김포)      능곡동 (고양)  open: left as it is
--   21539  삼성산주공정문             미성동             삼성동         open: both 관악, left as it is
--
-- Safe to run again (upsert). Do not run while the Tuesday 05:17 pipeline runs.
BEGIN;
INSERT INTO stop_location_fix (ars, stop_name, lat, lng, reason, note) VALUES
  ('14716', '사천고가앞', 37.567173, 126.917561, 'manual', 'review 2026-11-18: route path (연남동); stop table had 묵2동, 14803 m away'),
  ('05229', '건대입구역사거리.건대병원', 37.540784, 127.070878, 'manual', 'review 2026-11-18: route path (화양동); stop table had 구의2동, 2168 m away'),
  ('49061', '보통골동양공업사', 37.435073, 127.179017, 'manual', 'review 2026-11-18: route path (상대원1동); integrated had 성남동, 4440 m away')
ON CONFLICT (ars) DO UPDATE SET stop_name = EXCLUDED.stop_name, lat = EXCLUDED.lat, lng = EXCLUDED.lng,
    reason = EXCLUDED.reason, note = EXCLUDED.note;
SELECT reason, COUNT(*) FROM stop_location_fix GROUP BY 1 ORDER BY 1;
COMMIT;
