# Airflow Orchestration

DAGs that schedule the data pipeline.

## Current DAGs

| DAG | Schedule | Purpose |
|---|---|---|
| `od_correction` | 03:00 KST daily | Runs the four-step OD correction chain against api-server  |

## Running locally

```bash
docker compose --profile airflow up -d
```

Web UI: `http://localhost:8081`

Create the HTTP connection the DAG depends on, either in the UI
(Admin → Connections) or on the command line:

```bash
docker exec seoul-transit-airflow-web airflow connections add transit_api \
  --conn-type http --conn-host api-server --conn-port 8080
```

The api-server must be running too, so start the `app` profile as well:

```bash
docker compose --profile app --profile airflow up -d
```

## Migration notes

Three DAGs existed in the predecessor project. Only one was migrated directly;
the reasoning for each is recorded here so the omissions are deliberate rather
than forgotten.

### preprocessing_dag.py → `od_correction_dag.py` (migrated)

Endpoint paths and HTTP methods changed when the API was rewritten under the
`com.ian.transit` package. The task chain and its ordering are unchanged.

### daily_od_dag.py (not migrated)

Collected `CardBusStatisticsServiceNew` into a `daily_od_data` table. That
table no longer exists — `PredictionFeatureService` was changed to read lag
features from `analysis_table_final` instead, and its source comment records
the switch. Migrating the DAG as written would populate a table nothing reads.

Re-adding daily collection is reasonable future work, but it needs a decision
about which current table receives the rows.

### hourly_od_dag.py (not migrated)

Collected `CardBusTimeNew` and pivoted the wide monthly format into hourly
rows. `pipelines/hourly_od_estimation/load_hourly_boarding.py` already does
this and is the maintained implementation.

What is genuinely missing is scheduling, not collection logic. A thin DAG that
invokes the existing loader would add value; duplicating the API-parsing code
inside a DAG would not.
