# Phase 6.3 Local - Hourly Stop Pattern and Demand (Phase 6.3 Local 시간대별 정류장 패턴 및 수요)

> Korean comments are added in parentheses for review and handoff. (승하차 시간대 패턴과 정류장 수요를 만드는 Phase 6.3 실행 문서입니다.)

## Goal (목표)

Phase 6.3 Local extends Phase 6.2 by adding **alighting patterns** and producing a reusable hourly stop demand table.

LSTM and long-sequence modeling are intentionally excluded from this local phase because they should run after cloud migration.

## Scope (범위)

Included:

```text
1. boarding + alighting day-type hourly pattern
2. dow_hourly_stop_pattern table
3. estimated_hourly_stop_demand table
4. validation SQL
```

Excluded:

```text
1. LSTM
2. long-term sequence modeling
3. large-scale OD analysis
4. cloud model registry
```

## Recommended Location (권장 위치)

```text
SeoulTransitPlatform/
└── pipelines/
    └── hourly_stop_pattern/
```

## Run Order (실행 순서)

### 1. Create tables (테이블 생성)

```bash
psql -d Seoul_Transit -U postgres -f db_schema_phase6_3_local.sql
```

### 2. Solve boarding/alighting patterns (승하차 패턴 계산)

```bash
python solve_dow_hourly_stop_pattern.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --min-months 4 ^
  --batch-size 50000
```

### 3. Generate estimated hourly stop demand (추정 시간대 정류장 수요 생성)

```bash
python estimate_hourly_stop_demand.py ^
  --db-url "postgresql://postgres:${DB_PASSWORD}@localhost:5432/Seoul_Transit" ^
  --batch-size 50000
```

### 4. Validate (검증)

Run `validation_queries_phase6_3.sql`.
