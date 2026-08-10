@echo off
REM EN: Example runner. Edit paths and values before use.
REM KR: 실행 예시 파일입니다. 사용 전 경로와 값을 수정하세요.

REM EN: Set credentials in your shell/session BEFORE running this script.
REM     Never write real passwords or API keys into this file — it is
REM     committed to version control.
REM KR: 이 스크립트를 실행하기 전에 셸/세션에서 자격 증명을 설정하세요.
REM     이 파일은 버전 관리에 커밋되므로 실제 비밀번호나 API 키를 절대
REM     이 파일에 적지 마세요.
REM
REM   set DB_PASSWORD=...
REM   set SEOUL_API_KEY=...

if "%DB_PASSWORD%"=="" (
    echo ERROR: DB_PASSWORD is not set. Run: set DB_PASSWORD=your_password
    exit /b 1
)

set DB_URL=postgresql://postgres:%DB_PASSWORD%@localhost:5432/Seoul_Transit
set HOLIDAY_CSV=서울특별시 양천구_공휴일 목록_20251127.csv

REM 1. Load one month from Seoul API
REM python load_hourly_boarding.py --db-url "%DB_URL%" --api-key "%SEOUL_API_KEY%" --use-ym 202501

REM 2. Build month day count
python build_month_day_count.py --db-url "%DB_URL%" --start-ym 202501 --end-ym 202512 --holiday-csv "%HOLIDAY_CSV%"

REM 3. Solve day-of-week hourly ratios
python solve_dow_hourly_ratio.py --db-url "%DB_URL%" --min-months 3

REM 4. Estimate hourly OD
python estimate_hourly_od.py --db-url "%DB_URL%" --start-date 20250101 --end-date 20251231 --holiday-csv "%HOLIDAY_CSV%"

pause
