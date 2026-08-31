# Phase 6.2 - Hourly OD Estimation Pipeline (Phase 6.2 시간대별 OD 추정 파이프라인)

> Korean comments are added in parentheses for review and handoff. (일 단위 OD를 시간대별 OD로 변환하는 Phase 6.2 실행 문서입니다.)

## Goal (목표)

Convert daily OD records from `analysis_table_final` into estimated hourly OD records.

```text
daily_od_passengers × day-type hourly ratio = estimated_hourly_od
```

Day type classes:

```text
MON / TUE / WED / THU / FRI / SAT / SUN_HOLIDAY
```

`SUN_HOLIDAY` merges Sundays and public holidays.

---

## Recommended Location (권장 위치)

```text
SeoulTransitPlatform/
└── pipelines/
    └── hourly_od_estimation/
```

---

## Pipeline Order (파이프라인 순서)

### 0. Create tables (테이블 생성)

```bash
psql -d Seoul_Transit -U postgres -f db_schema_phase6_2.sql
```

### 1. Load monthly hourly boarding/alighting API data (월별 시간대 승하차 API 데이터 적재)

```bash
python load_hourly_boarding.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --api-key "%SEOUL_API_KEY%" ^
  --use-ym 202501
```

### 2. Build month day counts (월별 요일 수 계산)

```bash
python build_month_day_count.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --start-ym 202501 ^
  --end-ym 202512 ^
  --holiday-csv "korea_public_holidays_2020_2026.csv"
```

### 3. Solve day-of-week hourly patterns (요일별 시간대 패턴 계산)

```bash
python solve_dow_hourly_ratio.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --min-months 3
```

### 4. Estimate hourly OD (시간대별 OD 추정)

```bash
python estimate_hourly_od.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --start-date 20250101 ^
  --end-date 20251231 ^
  --holiday-csv "korea_public_holidays_2020_2026.csv"
```

---

## Method (방법)

For each route-stop-hour:

```text
A x = b
```

Where:

```text
A = month × day_type_count matrix
x = estimated average boarding count by day type
b = monthly hourly boarding count
```

Then:

```text
ratio(day_type, hour)
= avg_boarding(day_type, hour) / sum_hour(avg_boarding(day_type, hour))
```

Finally:

```text
estimated_hourly_od
= daily_od_passengers × ratio(day_type, hour)
```
