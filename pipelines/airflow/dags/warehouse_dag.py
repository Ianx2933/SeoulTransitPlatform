"""
Warehouse DAG

Copies the local PostgreSQL source into BigQuery staging and rebuilds the dbt
models on top of it:

    preflight -> export -> load -> dbt_build -> cleanup

The work itself lives in the transit-bigquery repository, mounted read-only at
/opt/transit_bigquery. This DAG only orders the steps, passes connection
details through the environment, and decides what happens on failure.

Each step can be re-run safely:

- export   writes a new snapshot folder per run; a retry deletes its own
           half-written folder first (--overwrite).
- load     records each table it has loaded in the folder, so a retry skips
           tables already done, and reuses a fixed BigQuery job ID so a
           submission that reached BigQuery is not loaded twice. --replace
           truncates the previous month's staging tables.
- dbt      `dbt build` runs models and tests together. A failing test stops
           the build, so the transit_marts dataset is not left half-updated
           with data that failed its checks.

The export folder is deleted only after dbt succeeds. On failure it stays on
the airflow_exports volume, so the manifest and load state can be inspected.

There is no schedule yet. The local PostgreSQL database is not refreshed
automatically (the monthly collector writes to Cloud SQL), so a scheduled run
would copy the same data every time. The schedule is added once the upstream
collection and estimation steps are part of this DAG.

Required environment (set in .env, passed by docker-compose.yaml):
    WAREHOUSE_PG_PASSWORD   password of the Windows PostgreSQL install
    WAREHOUSE_BQ_PROJECT    GCP project holding transit_staging
"""

from datetime import timedelta

import pendulum
from airflow import DAG
from airflow.operators.bash import BashOperator


KST = pendulum.timezone("Asia/Seoul")

VENV = "/home/airflow/warehouse-venv/bin"
REPO = "/opt/transit_bigquery"
EXPORT = "/opt/airflow/exports/{{ ts_nodash }}"

# Shared shell prelude. The password is read from the container environment at
# run time and never written into the command, so it does not appear in the
# rendered template shown in the Airflow UI.
PRELUDE = """
set -euo pipefail
export PGHOST="${WAREHOUSE_PG_HOST:-host.docker.internal}"
export PGPORT="${WAREHOUSE_PG_PORT:-5432}"
export PGUSER="${WAREHOUSE_PG_USER:-postgres}"
export PGDATABASE="${WAREHOUSE_PG_DATABASE:-Seoul_Transit}"
export PGPASSWORD="${WAREHOUSE_PG_PASSWORD:?WAREHOUSE_PG_PASSWORD is not set in .env}"
: "${WAREHOUSE_BQ_PROJECT:?WAREHOUSE_BQ_PROJECT is not set in .env}"
"""

default_args = {
    "owner": "airflow",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}


with DAG(
    dag_id="warehouse",
    default_args=default_args,
    description="PostgreSQL -> BigQuery staging -> dbt models",
    schedule=None,
    start_date=pendulum.datetime(2026, 11, 1, tz=KST),
    catchup=False,
    max_active_runs=1,
    tags=["seoul", "bigquery", "dbt"],
) as dag:

    # Fails in seconds if PostgreSQL or BigQuery cannot be reached, instead of
    # ten minutes into an export.
    preflight = BashOperator(
        task_id="preflight",
        bash_command=PRELUDE + f"""
"{VENV}/python" - <<'PY'
import os
import psycopg2
from google.cloud import bigquery

with psycopg2.connect(connect_timeout=10) as conn, conn.cursor() as cur:
    cur.execute("SELECT MIN(use_ym), MAX(use_ym), COUNT(DISTINCT use_ym) FROM hourly_bus_stop_passenger")
    first, last, months = cur.fetchone()
    print(f"PostgreSQL {{os.environ['PGHOST']}}: {{months}} months, {{first}}..{{last}}")

project = os.environ["WAREHOUSE_BQ_PROJECT"]
ds = bigquery.Client(project=project, location="asia-northeast3").get_dataset(project + ".transit_staging")
print(f"BigQuery {{ds.full_dataset_id}} in {{ds.location}}")
PY
""",
        retries=0,
        execution_timeout=timedelta(minutes=5),
    )

    export = BashOperator(
        task_id="export",
        bash_command=PRELUDE + f"""
"{VENV}/python" "{REPO}/transit_bigquery.py" export --out "{EXPORT}" --overwrite
""",
        execution_timeout=timedelta(minutes=60),
    )

    load = BashOperator(
        task_id="load",
        bash_command=PRELUDE + f"""
"{VENV}/python" "{REPO}/transit_bigquery.py" load --folder "{EXPORT}" \\
    --project "$WAREHOUSE_BQ_PROJECT" --replace
""",
        retries=2,
        execution_timeout=timedelta(minutes=30),
    )

    # No retry: a dbt failure is almost always a failing test, and rerunning
    # the same data produces the same failure.
    dbt_build = BashOperator(
        task_id="dbt_build",
        bash_command=PRELUDE + f"""
export DBT_TARGET_PATH=/tmp/dbt/target DBT_LOG_PATH=/tmp/dbt/logs
"{VENV}/dbt" build --target prod \\
    --project-dir "{REPO}/transit_dbt" --profiles-dir /home/airflow/.dbt
""",
        retries=0,
        execution_timeout=timedelta(minutes=30),
    )

    cleanup = BashOperator(
        task_id="cleanup",
        bash_command=f'rm -rf "{EXPORT}"',
        retries=0,
    )

    preflight >> export >> load >> dbt_build >> cleanup
