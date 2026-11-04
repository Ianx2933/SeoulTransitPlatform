# Airflow Orchestration

DAGs that schedule the data pipeline.

## Current DAGs

| DAG | Schedule | Purpose |
|---|---|---|
| `od_correction` | 03:00 KST daily | Runs the four-step OD correction chain against api-server |
| `warehouse` | Manual | Copies the local PostgreSQL source into BigQuery staging and rebuilds the dbt models |

## Running locally

The Airflow image adds dbt and the BigQuery client in a separate virtualenv
(see `Dockerfile`). Build it and start only the Airflow services:

```bash
docker compose --profile airflow build
docker compose --profile airflow up -d airflow-webserver airflow-scheduler
```

`airflow-init` runs first on its own: it migrates the metadata database and
creates the admin user, and the webserver and scheduler wait for it to finish.

Naming the services matters. `docker compose --profile airflow up -d` on its
own also starts the `postgres` service, whose port 5432 collides with a
PostgreSQL install already running on the host.

Web UI: `http://localhost:8081` (user `admin`, password `AIRFLOW_ADMIN_PASSWORD`
from `.env`).

Stop with `docker compose --profile airflow stop`. Do not use `down -v`: it
deletes every named volume, including the `postgres` service's data.

## `warehouse`

```
preflight -> export -> load -> dbt_build -> cleanup
```

| Task | What it does | On failure |
|---|---|---|
| `preflight` | Connects to PostgreSQL and BigQuery and prints what it found | Stops in seconds instead of partway into an export |
| `export` | Read-only snapshot of 13 tables to Parquet, with checksums and per-month totals | Retried once; the retry deletes its own partial folder |
| `load` | Loads the snapshot into `transit_staging`, replacing last month's tables, then reconciles per month | Retried twice; tables already loaded are skipped and a job that reached BigQuery is not resubmitted |
| `dbt_build` | `dbt build --target prod`: models and tests together | Not retried; a failing test stops the build and `transit_marts` keeps its previous state |
| `cleanup` | Deletes the export folder | Runs only after dbt succeeds, so a failed run's files stay for inspection |

The loader and the dbt project live in the private `transit-bigquery`
repository, which is mounted read-only into the scheduler. The DAG passes
connection details through the environment; the password is read inside the
task at run time and does not appear in the rendered command in the UI.

The DAG has no schedule. The monthly collector writes new data to Cloud SQL,
not to the local database this DAG reads, so a scheduled run would copy the
same data each time. A schedule follows once local collection and estimation
are added in front of `export`.

### Setup

Add to `.env`:

```bash
WAREHOUSE_PG_PASSWORD=...        # password of the PostgreSQL install on the host
WAREHOUSE_BQ_PROJECT=...         # GCP project holding transit_staging
```

The scheduler mounts three host paths. The defaults suit Windows; override
them in `.env` elsewhere:

| Variable | Default | Mounted at |
|---|---|---|
| `TRANSIT_BIGQUERY_DIR` | `../transit_bigquery` | `/opt/transit_bigquery` |
| `DBT_PROFILES_HOST_DIR` | `%USERPROFILE%/.dbt` | `/home/airflow/.dbt` |
| `GCLOUD_ADC_FILE` | `%APPDATA%/gcloud/application_default_credentials.json` | `/home/airflow/gcloud/...` |

The container reaches the host's PostgreSQL at `host.docker.internal`. If
`preflight` reports a refused connection, PostgreSQL is listening on
localhost only: set `listen_addresses = '*'` in `postgresql.conf` and allow the
Docker subnet in `pg_hba.conf` with `scram-sha-256`.

## `od_correction`

Create the HTTP connection the DAG depends on, either in the UI
(Admin → Connections) or on the command line:

```bash
docker exec seoul-transit-airflow-web airflow connections add transit_api \
  --conn-type http --conn-host api-server --conn-port 8080
```

The api-server must be running too, so start the `app` profile as well.

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
