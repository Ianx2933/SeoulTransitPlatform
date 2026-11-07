"""The collector reports which months it loaded, for the workflow that runs it.

A Cloud Run Job only reports success or failure. The weekly workflow needs to
know whether a new month arrived before it scales the database up and rebuilds
everything, so the collector writes a small JSON file that the workflow reads.
"""

import sys
from datetime import date

import pytest

from pipelines.hourly_od_estimation import load_hourly_boarding as loader
from pipelines.hourly_od_estimation.load_hourly_boarding import MonthNotPublished


def run(monkeypatch, latest, today, unpublished, state_uri="gs://bucket/state/run-1.json"):
    written = []

    class _Date(date):
        @classmethod
        def today(cls):
            return today

    def fake_load_month(engine, api_key, use_ym, *args):
        if use_ym in unpublished:
            raise MonthNotPublished(f"{use_ym} is not published yet")
        return 100

    monkeypatch.setattr(loader, "make_engine", lambda url: object())
    monkeypatch.setattr(loader, "latest_loaded_month", lambda engine: latest)
    monkeypatch.setattr(loader, "load_month", fake_load_month)
    monkeypatch.setattr(loader, "date", _Date)
    monkeypatch.setattr(loader, "load_dotenv", lambda: None)
    monkeypatch.setattr(loader, "write_run_state", lambda uri, state: written.append((uri, state)))
    monkeypatch.setenv("PIPELINE_DB_URL", "postgresql+psycopg2://u:p@127.0.0.1:1/x")
    monkeypatch.setenv("SEOUL_API_KEY", "test-key")
    if state_uri:
        monkeypatch.setenv("PIPELINE_STATE_URI", state_uri)
    else:
        monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    monkeypatch.setattr(sys, "argv", ["load_hourly_boarding"])
    loader.main()
    return written


def test_new_months_are_reported(monkeypatch, capsys):
    written = run(monkeypatch, "202609", date(2026, 12, 8), set())
    uri, state = written[0]
    assert uri == "gs://bucket/state/run-1.json"
    assert state["latest_before"] == "202609"
    assert state["loaded_months"] == ["202610", "202611"]
    assert state["latest_after"] == "202611"


def test_unpublished_month_is_not_reported_as_loaded(monkeypatch, capsys):
    _, state = run(monkeypatch, "202610", date(2026, 12, 1), {"202611"})[0]
    assert state["loaded_months"] == []
    assert state["latest_after"] == "202610"


def test_up_to_date_run_still_reports(monkeypatch, capsys):
    _, state = run(monkeypatch, "202611", date(2026, 12, 20), set())[0]
    assert state["loaded_months"] == []
    assert "Up to date" in capsys.readouterr().out


def test_without_the_variable_nothing_is_written(monkeypatch, capsys):
    assert run(monkeypatch, "202609", date(2026, 11, 8), set(), state_uri=None) == []
    assert "RUN_STATE" in capsys.readouterr().out


def test_state_uri_must_be_a_gcs_object():
    with pytest.raises(ValueError):
        loader.write_run_state("/tmp/state.json", {})
    with pytest.raises(ValueError):
        loader.write_run_state("gs://bucket-only", {})
