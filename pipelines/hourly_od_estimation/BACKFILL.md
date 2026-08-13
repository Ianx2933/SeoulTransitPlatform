# Historical Backfill

How to load past months of hourly bus stop passenger data.

## Why there is no separate daily table

The predecessor project ran two loaders against two APIs:

| API | Target table |
|---|---|
| `CardBusStatisticsServiceNew` | `daily_od_data` |
| `CardBusTimeNew` | `hourly_bus_stop_passenger` |

`daily_od_data` is no longer used. `PredictionFeatureService` was changed to
read lag features from `analysis_table_final` — the corrected OD table — rather
than from raw daily aggregates, and nothing else referenced the daily table.

A daily figure is a sum of hourly rows, so storing both would mean the same
fact lives in two places and can disagree:

```sql
SELECT use_ym, route_no, stop_ars,
       SUM(boarding_passengers)  AS boarding,
       SUM(alighting_passengers) AS alighting
FROM hourly_bus_stop_passenger
GROUP BY use_ym, route_no, stop_ars;
```

## Backfill

The API is monthly, so a backfill iterates months rather than days.

```bash
export PIPELINE_DB_URL='postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'
export SEOUL_API_KEY='your-key'

python pipelines/hourly_od_estimation/load_hourly_boarding.py \
  --start-ym 202502 --end-ym 202512 \
  --continue-on-error
```

PowerShell:

```powershell
$env:PIPELINE_DB_URL='postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'
$env:SEOUL_API_KEY='your-key'

python pipelines/hourly_od_estimation/load_hourly_boarding.py `
  --start-ym 202502 --end-ym 202512 `
  --continue-on-error
```

Single month, unchanged from before:

```bash
python pipelines/hourly_od_estimation/load_hourly_boarding.py --use-ym 202501
```

## Arguments

| Argument | Default | Notes |
|---|---|---|
| `--db-url` | `PIPELINE_DB_URL` | Falls back to the environment variable |
| `--api-key` | `SEOUL_API_KEY` | Falls back to the environment variable |
| `--use-ym` | — | Single month, `YYYYMM` |
| `--start-ym` / `--end-ym` | — | Backfill range, inclusive |
| `--route-no` | all routes | Restrict to one route |
| `--page-size` | 1000 | API rows per request |
| `--sleep` | 0.1 | Seconds between requests |
| `--continue-on-error` | off | Record failed months and keep going |

`--use-ym` and `--start-ym`/`--end-ym` are mutually exclusive. Supplying
neither, both, or only one half of the range is rejected rather than assumed —
a typo in `--start-ym` should not quietly load a single month instead.

## Re-running is safe

The upsert uses `ON CONFLICT (use_ym, route_no, stop_ars, hour)`, so re-running
a month updates rows instead of duplicating them. A failed backfill can simply
be re-run over the same range.

## Expect some months to fail

A long range can include months the API has no data for, or transient network
failures. Without `--continue-on-error` the first failure stops everything,
losing nothing already committed but requiring a manual restart.

With the flag, failures are collected and printed at the end, and the process
exits non-zero so a scheduler still sees the failure:

```text
Done. Months processed: 9/11
Inserted/updated unpivoted hourly rows: 1284000

Failed months (2):
  202508: Seoul API error: INFO-200 no data
  202511: HTTPSConnectionPool timeout
```

Re-run just those months afterwards.

## Scheduling

Only the OD correction DAG has been migrated so far. Scheduling this loader
monthly is listed as next work in the README; the DAG would call this script
rather than reimplementing the API parsing.