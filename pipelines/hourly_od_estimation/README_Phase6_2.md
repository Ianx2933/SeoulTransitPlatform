# Phase 6.2 - Hourly OD Estimation Pipeline

## Goal

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

## Recommended Location

```text
SeoulTransitPlatform/
└── pipelines/
    └── hourly_od_estimation/
```

---

## Pipeline Order

### 0. Create tables

```bash
psql -d Seoul_Transit -U postgres -f db_schema_phase6_2.sql
```

### 1. Load monthly hourly boarding/alighting API data

```bash
python load_hourly_boarding.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --api-key "%SEOUL_API_KEY%" ^
  --use-ym 202501
```

### 2. Build month day counts

```bash
python build_month_day_count.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --start-ym 202501 ^
  --end-ym 202512 ^
  --holiday-csv "서울특별시 양천구_공휴일 목록_20251127.csv"
```

### 3. Solve day-of-week hourly patterns

```bash
python solve_dow_hourly_ratio.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --min-months 3
```

### 4. Estimate hourly OD

```bash
python estimate_hourly_od.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --start-date 20250101 ^
  --end-date 20251231 ^
  --holiday-csv "서울특별시 양천구_공휴일 목록_20251127.csv"
```

---

## Method

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
