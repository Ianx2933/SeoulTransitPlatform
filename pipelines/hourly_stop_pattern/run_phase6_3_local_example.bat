@echo off
REM Example runner. (실행 예시)

set DB_URL=postgresql://postgres:330218@localhost:5432/Seoul_Transit

python solve_dow_hourly_stop_pattern.py --db-url "%DB_URL%" --min-months 4 --batch-size 50000

python estimate_hourly_stop_demand.py --db-url "%DB_URL%" --batch-size 50000

pause
