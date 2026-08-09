#!/usr/bin/env bash
set -euo pipefail

# ============================================================
# Repository hygiene cleanup. (저장소 정리 스크립트입니다.)
#  - Untracks node_modules from Git without deleting local files. (로컬 파일은 삭제하지 않고 Git 추적만 해제합니다.)
#  - Untracks large generated data files. (대용량 생성 데이터 파일의 Git 추적을 해제합니다.)
#  - Deletes known stray shell-artifact files. (셸 실수로 생긴 불필요한 파일만 삭제합니다.)
#
# Run from the repository root. (저장소 루트에서 실행하세요.)
# The --ignore-unmatch flag keeps this script idempotent. (이미 추적 해제된 파일이 있어도 실패하지 않게 합니다.)
# ============================================================

git rm -r --cached --ignore-unmatch services/web-client/node_modules

git rm --cached --ignore-unmatch data/curated/od_curated_20251014.csv
git rm --cached --ignore-unmatch data/curated/od_curated_20251111.csv
git rm -r --cached --ignore-unmatch data/inspection
git rm --cached --ignore-unmatch data/raw/final_query_normalized.csv
git rm --cached --ignore-unmatch data/raw/merged_od_data.xlsx
git rm --cached --ignore-unmatch data/reference/od_reference_251014.csv

# Stray empty files created by accidental shell redirection. (셸 리다이렉션 실수로 생긴 빈 파일입니다.)
git rm --cached --ignore-unmatch "services/api-server/java"
rm -f "Number(b[metric]"
rm -f services/api-server/java

echo
echo "Done. Review changes with: git status"
echo 'Then commit: git commit -m "chore: untrack generated data and stray artifacts"'
