@echo off
REM Lightweight Phase 6.5 runner. (경량 Phase 6.5 실행 예시)

set DB_URL=postgresql://postgres:330218@localhost:5432/Seoul_Transit
set SUBWAY_CSV_DIR=C:\Users\miyum\SeoulTransitPlatform\data\subway_hourly
set HOLIDAY_CSV=C:\Users\miyum\SeoulTransitPlatform\pipelines\hourly_od_estimation\공휴일목록_202026.csv

python build_subway_hourly_station_demand_light.py --db-url "%DB_URL%" --input-dir "%SUBWAY_CSV_DIR%" --holiday-csv "%HOLIDAY_CSV%" --batch-size 50000

python build_integrated_hourly_transit_demand_light.py --db-url "%DB_URL%" --batch-size 50000

pause
