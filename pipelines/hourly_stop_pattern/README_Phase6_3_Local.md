# Phase 6.3 Local - Hourly Stop Pattern and Demand

## Goal

Phase 6.3 Local extends Phase 6.2 by adding **alighting patterns** and producing a reusable hourly stop demand table.

LSTM and long-sequence modeling are intentionally excluded from this local phase because they should run after cloud migration.

## Scope

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

## Recommended Location

```text
SeoulTransitPlatform/
└── pipelines/
    └── hourly_stop_pattern/
```

## Run Order

### 1. Create tables

```bash
psql -d Seoul_Transit -U postgres -f db_schema_phase6_3_local.sql
```

### 2. Solve boarding/alighting patterns

```bash
python solve_dow_hourly_stop_pattern.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --min-months 4 ^
  --batch-size 50000
```

### 3. Generate estimated hourly stop demand

```bash
python estimate_hourly_stop_demand.py ^
  --db-url "postgresql://postgres:330218@localhost:5432/Seoul_Transit" ^
  --batch-size 50000
```

### 4. Validate

Run `validation_queries_phase6_3.sql`.
