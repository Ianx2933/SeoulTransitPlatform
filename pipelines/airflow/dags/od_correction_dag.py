"""
OD Correction DAG

Runs the daily OD correction chain by calling the api-server correction
endpoints in order.

Migrated from the predecessor project's preprocessing_dag.py. The endpoint
paths changed when the API was rewritten:

    /api/busstop/fix-virtual-stop   ->  POST /api/od-correction/virtual-stop
    /api/busstop/fix-sequence       ->  POST /api/od-correction/sequence
    /api/busstop/fix-same-stop-od   ->  POST /api/od-correction/same-stop
    /api/busstop/deduplicate        ->  POST /api/od-correction/deduplicate

The method also changed from GET to POST, because these endpoints mutate
curated data.

Order matters. Each step assumes the previous one has completed:
sequence correction depends on virtual stops already being resolved, and
deduplication must run last because earlier steps can create duplicate rows.

Required Airflow connection:
    transit_api   HTTP connection pointing at the api-server

Required environment:
    ADMIN_API_TOKEN   guards the /api/od-correction endpoints
"""

import os
from datetime import datetime, timedelta

import pendulum
from airflow import DAG
from airflow.providers.http.operators.http import HttpOperator


KST = pendulum.timezone("Asia/Seoul")

HTTP_CONN_ID = "transit_api"

# The correction endpoints are admin-scoped and require a bearer token.
AUTH_HEADERS = {
    "Authorization": f"Bearer {os.environ.get('ADMIN_API_TOKEN', '')}",
}

default_args = {
    "owner": "airflow",
    "retries": 1,
    "retry_delay": timedelta(minutes=5),
}


def correction_task(task_id: str, endpoint: str) -> HttpOperator:
    """Build one correction step.

    Correction over a full day of rows is slow, so the timeout is generous.
    """

    return HttpOperator(
        task_id=task_id,
        http_conn_id=HTTP_CONN_ID,
        endpoint=f"{endpoint}?date={{{{ ds_nodash }}}}",
        method="POST",
        headers=AUTH_HEADERS,
        log_response=True,
        response_check=lambda response: response.status_code in (200, 201),
        extra_options={"timeout": 600},
    )


with DAG(
    dag_id="od_correction",
    default_args=default_args,
    description="Daily OD correction chain",
    schedule="0 3 * * *",
    start_date=datetime(2026, 4, 1, tzinfo=KST),
    catchup=False,
    max_active_runs=1,
    tags=["seoul", "bus", "correction"],
) as dag:

    fix_virtual_stop = correction_task(
        "fix_virtual_stop", "/api/od-correction/virtual-stop"
    )
    fix_sequence = correction_task(
        "fix_sequence", "/api/od-correction/sequence"
    )
    fix_same_stop = correction_task(
        "fix_same_stop", "/api/od-correction/same-stop"
    )
    deduplicate = correction_task(
        "deduplicate", "/api/od-correction/deduplicate"
    )

    fix_virtual_stop >> fix_sequence >> fix_same_stop >> deduplicate
