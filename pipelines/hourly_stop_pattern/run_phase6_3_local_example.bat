@echo off
REM Example runner.
REM Set DB_PASSWORD in your session first. Never write a real password here:
REM this file is committed to a public repository.
REM   set DB_PASSWORD=...

if "%DB_PASSWORD%"=="" (
    echo ERROR: DB_PASSWORD is not set. Run: set DB_PASSWORD=your_password
    exit /b 1
)

set DB_URL=postgresql://postgres:%DB_PASSWORD%@localhost:5432/Seoul_Transit

python solve_dow_hourly_stop_pattern.py --db-url "%DB_URL%" --min-months 4 --batch-size 50000

python estimate_hourly_stop_demand.py --db-url "%DB_URL%" --batch-size 50000

pause
