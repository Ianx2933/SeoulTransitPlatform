@echo off
REM Lightweight Phase 6.5 runner.
REM Set DB_PASSWORD in your session first. Never write a real password here:
REM this file is committed to a public repository.
REM   set DB_PASSWORD=...

if "%DB_PASSWORD%"=="" (
    echo ERROR: DB_PASSWORD is not set. Run: set DB_PASSWORD=your_password
    exit /b 1
)

set DB_URL=postgresql://postgres:%DB_PASSWORD%@localhost:5432/Seoul_Transit
set SUBWAY_CSV_DIR=%~dp0..\..\data\subway_hourly
set HOLIDAY_CSV=%~dp0..\hourly_od_estimation\korea_public_holidays_2020_2026.csv

python build_subway_hourly_station_demand_light.py --db-url "%DB_URL%" --input-dir "%SUBWAY_CSV_DIR%" --holiday-csv "%HOLIDAY_CSV%" --batch-size 50000

python build_integrated_hourly_transit_demand_light.py --db-url "%DB_URL%" --batch-size 50000

pause
