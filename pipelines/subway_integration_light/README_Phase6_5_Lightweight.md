# Phase 6.5 Lightweight - Subway Integration for Local Environment (Phase 6.5 경량 지하철 통합 로컬 환경)

> Korean comments are added in parentheses for review and handoff. (로컬 환경에서 지하철 수요를 경량 통합하는 Phase 6.5 실행 문서입니다.)

## Goal (목표)

Build a lightweight bus + subway hourly demand layer without storing large subway raw tables.

## Design (설계)

```text
Subway CSV
→ parse
→ attach day_type
→ aggregate to line/station/day_type/hour
→ save subway_hourly_station_demand_light
→ combine with bus estimated_hourly_stop_demand
→ integrated_hourly_transit_demand_light
```

## What this phase does NOT do (이 단계에서 하지 않는 것)

```text
- No raw subway table
- No transfer inference
- No trip-chain inference
- No full OD expansion
```

## Recommended Location (권장 위치)

```text
SeoulTransitPlatform/
└── pipelines/
    └── subway_integration_light/
```

## Run Order (실행 순서)

### 1. Install requirements (요구 패키지 설치)

```bash
pip install -r requirements_phase6_5_light.txt
```

### 2. Create lightweight tables (경량 테이블 생성)

Use pgAdmin Query Tool or psql:

```bash
"C:\Program Files\PostgreSQL\18\bin\psql.exe" -d Seoul_Transit -U postgres -f db_schema_phase6_5_light.sql
```

### 3. Build lightweight subway demand directly from CSV (CSV에서 지하철 수요 생성)

```bash
python build_subway_hourly_station_demand_light.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --input-dir ".\data\subway_hourly" ^
  --holiday-csv ".\pipelines\hourly_od_estimation\공휴일목록_202026.csv" ^
  --batch-size 50000
```

### 4. Build lightweight integrated demand (통합 수요 생성)

```bash
python build_integrated_hourly_transit_demand_light.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --batch-size 50000
```

### 5. Validate (검증)

Run `validation_queries_phase6_5_light.sql`.

## Cloud Migration Note (클라우드 이전 메모)

Full raw subway ingestion should be implemented later in cloud storage/database.
