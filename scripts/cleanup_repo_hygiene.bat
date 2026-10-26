@echo off
setlocal

REM ============================================================
REM Repository hygiene cleanup. 
REM  - Untracks node_modules from Git without deleting local files.
REM  - Untracks large generated data files.
REM  - Deletes known stray shell-artifact files.
REM
REM Run from the repository root.
REM The --ignore-unmatch flag keeps this script idempotent.
REM ============================================================

git rm -r --cached --ignore-unmatch services/web-client/node_modules

git rm --cached --ignore-unmatch data/curated/od_curated_20251014.csv
git rm --cached --ignore-unmatch data/curated/od_curated_20251111.csv
git rm -r --cached --ignore-unmatch data/inspection
git rm --cached --ignore-unmatch data/raw/final_query_normalized.csv
git rm --cached --ignore-unmatch data/raw/merged_od_data.xlsx
git rm --cached --ignore-unmatch data/reference/od_reference_251014.csv

REM Stray empty files created by accidental shell redirection.
git rm --cached --ignore-unmatch "services/api-server/java"
del "Number(b[metric]" 2>nul
del "services\api-server\java" 2>nul

echo.
echo Done. Review changes with: git status
echo Then commit: git commit -m "chore: untrack generated data and stray artifacts"
pause
