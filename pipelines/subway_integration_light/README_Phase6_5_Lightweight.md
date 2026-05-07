# Phase 6.5 Lightweight - Subway Integration for Local Environment

## Goal

Build a lightweight bus + subway hourly demand layer without storing large subway raw tables.

## Design

```text
Subway CSV
→ parse
→ attach day_type
→ aggregate to line/station/day_type/hour
→ save subway_hourly_station_demand_light
→ combine with bus estimated_hourly_stop_demand
→ integrated_hourly_transit_demand_light
```

## What this phase does NOT do

```text
- No raw subway table
- No transfer inference
- No trip-chain inference
- No full OD expansion
```

## Recommended Location

```text
SeoulTransitPlatform/
└── pipelines/
    └── subway_integration_light/
```

## Run Order

### 1. Install requirements

```bash
pip install -r requirements_phase6_5_light.txt
```

### 2. Create lightweight tables

Use pgAdmin Query Tool or psql:

```bash
"C:\Program Files\PostgreSQL\18\bin\psql.exe" -d Seoul_Transit -U postgres -f db_schema_phase6_5_light.sql
```

### 3. Build lightweight subway demand directly from CSV

```bash
python build_subway_hourly_station_demand_light.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --input-dir "C:\Users\miyum\SeoulTransitPlatform\data\subway_hourly" ^
  --holiday-csv "C:\Users\miyum\SeoulTransitPlatform\pipelines\hourly_od_estimation\공휴일목록_202026.csv" ^
  --batch-size 50000
```

### 4. Build lightweight integrated demand

```bash
python build_integrated_hourly_transit_demand_light.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --batch-size 50000
```

### 5. Validate

Run `validation_queries_phase6_5_light.sql`.

## Cloud Migration Note

Full raw subway ingestion should be implemented later in cloud storage/database.
