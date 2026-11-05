"""Pipeline scripts take the database URL from PIPELINE_DB_URL when --db-url is absent.

Cloud Run Jobs pass arguments in plain view in the job definition, so the URL,
which carries the password, has to arrive through a secret-backed environment
variable instead. These tests run each script as the job would and stop at the
first database connection, which goes to a closed local port.
"""

import os
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
UNREACHABLE = "postgresql+psycopg2://user:secret@127.0.0.1:1/none"

# (how to start it, extra arguments it needs)
SCRIPTS = [
    (["-m", "pipelines.hourly_od_estimation.build_month_day_count"], ["--start-ym", "202601", "--end-ym", "202601"]),
    (["-m", "pipelines.hourly_od_estimation.solve_dow_hourly_ratio"], []),
    (["-m", "pipelines.hourly_od_estimation.estimate_hourly_od"], []),
    (["-m", "pipelines.hourly_od_estimation.validate_rebuild"], ["--start-date", "20251111", "--end-date", "20251111"]),
    (["pipelines/hourly_stop_pattern/solve_dow_hourly_stop_pattern.py"], []),
    (["pipelines/hourly_stop_pattern/estimate_hourly_stop_demand.py"], []),
    (["pipelines/subway_integration_light/build_integrated_hourly_transit_demand_light.py"], []),
]
IDS = [s[0][-1].rsplit("/", 1)[-1].rsplit(".", 1)[-1] for s in SCRIPTS]


def run(start, extra, env_url):
    env = {k: v for k, v in os.environ.items() if k != "PIPELINE_DB_URL"}
    if env_url is not None:
        env["PIPELINE_DB_URL"] = env_url
    return subprocess.run(
        [sys.executable, *start, *extra],
        cwd=ROOT, env=env, capture_output=True, text=True, timeout=120,
    )


@pytest.mark.parametrize("start,extra", SCRIPTS, ids=IDS)
def test_url_comes_from_environment(start, extra):
    result = run(start, extra, UNREACHABLE)
    assert "--db-url" not in result.stderr, result.stderr
    # Got past argument parsing and tried to connect.
    assert result.returncode != 0
    assert "127.0.0.1" in result.stderr or "Connection refused" in result.stderr or "OperationalError" in result.stderr, result.stderr
    # The password must not be echoed into logs.
    assert "secret" not in result.stdout and "secret" not in result.stderr


@pytest.mark.parametrize("start,extra", SCRIPTS, ids=IDS)
def test_missing_url_is_an_argument_error(start, extra):
    result = run(start, extra, None)
    assert result.returncode == 2, result.stderr
    assert "--db-url" in result.stderr
