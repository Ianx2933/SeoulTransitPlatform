"""The ratio and stop pattern solvers survive a dropped database connection.

On 2026-11-06 a Cloud Run job lost its Cloud SQL connection after 45 minutes
while the database kept running. With one transaction over all batches the
whole run was lost. Each batch now commits on its own and a batch whose
connection drops is retried on fresh connections.
"""

import sys
from pathlib import Path

import pytest
from sqlalchemy.exc import OperationalError

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pipelines" / "hourly_stop_pattern"))

from pipelines.hourly_od_estimation import solve_dow_hourly_ratio as ratio  # noqa: E402
import solve_dow_hourly_stop_pattern as stop_pattern  # noqa: E402

SOLVERS = [ratio, stop_pattern]


class FakeEngine:
    def __init__(self):
        self.disposed = 0

    def dispose(self):
        self.disposed += 1


def dropped():
    return OperationalError("SELECT ...", {}, Exception("server closed the connection unexpectedly"))


@pytest.mark.parametrize("module", SOLVERS, ids=["ratio", "stop_pattern"])
def test_dropped_connection_is_retried_on_fresh_connections(module, capsys):
    engine, waits, calls = FakeEngine(), [], []

    def work():
        calls.append(1)
        if len(calls) < 3:
            raise dropped()
        return "ok"

    assert module.with_retry(engine, work, "routes 1-25", sleep=waits.append) == "ok"
    assert len(calls) == 3
    assert engine.disposed == 2
    assert waits == [module.RETRY_WAIT_SECONDS] * 2
    assert "connection lost" in capsys.readouterr().out


@pytest.mark.parametrize("module", SOLVERS, ids=["ratio", "stop_pattern"])
def test_gives_up_after_the_last_attempt(module, capsys):
    engine = FakeEngine()

    def work():
        raise dropped()

    with pytest.raises(OperationalError):
        module.with_retry(engine, work, "routes 1-25", attempts=3, sleep=lambda s: None)
    assert engine.disposed == 2


@pytest.mark.parametrize("module", SOLVERS, ids=["ratio", "stop_pattern"])
def test_other_errors_are_not_retried(module):
    engine, calls = FakeEngine(), []

    def work():
        calls.append(1)
        raise ValueError("bad data")

    with pytest.raises(ValueError):
        module.with_retry(engine, work, "routes 1-25", sleep=lambda s: None)
    assert calls == [1]
    assert engine.disposed == 0
