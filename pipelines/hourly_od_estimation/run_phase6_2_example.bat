@echo off
REM EN: Example runner. Edit paths and values before use.
REM KR: 실행 예시 파일입니다. 사용 전 경로와 값을 수정하세요.

set DB_URL=postgresql://postgres:330218@localhost:5432/Seoul_Transit
set HOLIDAY_CSV=서울특별시 양천구_공휴일 목록_20251127.csv

REM Optional if not passing --api-key
REM set SEOUL_API_KEY=YOUR_KEY_HERE

REM 1. Load one month from Seoul API
REM python load_hourly_boarding.py --db-url "%DB_URL%" --api-key "%SEOUL_API_KEY%" --use-ym 202501

REM 2. Build month day count
python build_month_day_count.py --db-url "%DB_URL%" --start-ym 202501 --end-ym 202512 --holiday-csv "%HOLIDAY_CSV%"

REM 3. Solve day-of-week hourly ratios
python solve_dow_hourly_ratio.py --db-url "%DB_URL%" --min-months 3

REM 4. Estimate hourly OD
python estimate_hourly_od.py --db-url "%DB_URL%" --start-date 20250101 --end-date 20251231 --holiday-csv "%HOLIDAY_CSV%"

pause
