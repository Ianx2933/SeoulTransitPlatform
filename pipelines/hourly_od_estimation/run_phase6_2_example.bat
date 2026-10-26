@echo off
REM: Example runner. Edit paths and values before use.

REM : Set credentials in your shell/session BEFORE running this script.
REM     Never write real passwords or API keys into this file — it is
REM     committed to version control.
REM
REM   set DB_PASSWORD=...
REM   set SEOUL_API_KEY=...

if "%DB_PASSWORD%"=="" (
    echo ERROR: DB_PASSWORD is not set. Run: set DB_PASSWORD=your_password
    exit /b 1
)

set DB_URL=postgresql://postgres:%DB_PASSWORD%@localhost:5432/Seoul_Transit
set HOLIDAY_CSV=korea_public_holidays_2020_2026.csv

REM 1. Load one month from Seoul API
REM python load_hourly_boarding.py --db-url "%DB_URL%" --api-key "%SEOUL_API_KEY%" --use-ym 202501

REM 2. Build month day count
python build_month_day_count.py --db-url "%DB_URL%" --start-ym 202501 --end-ym 202512 --holiday-csv "%HOLIDAY_CSV%"

REM 3. Solve day-of-week hourly ratios
python solve_dow_hourly_ratio.py --db-url "%DB_URL%" --min-months 3

REM 4. Estimate hourly OD
python estimate_hourly_od.py --db-url "%DB_URL%" --start-date 20250101 --end-date 20251231 --holiday-csv "%HOLIDAY_CSV%"

pause
