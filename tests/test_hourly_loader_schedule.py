"""Scheduled runs catch up on every month after the latest one loaded.

Asking only for "last month" lost data. The source publishes each month at an
irregular date, so with a monthly trigger a month still unpublished on the run
date was never requested again: on 5 November 2026-10 was not out yet, and the
5 December run would have asked for 2026-11 only.
"""

import sys
from datetime import date

import pytest

from pipelines.hourly_od_estimation import load_hourly_boarding as loader
from pipelines.hourly_od_estimation.load_hourly_boarding import (
    MonthNotPublished,
    months_to_collect,
    previous_month,
)


def test_previous_month_crosses_year_boundary():
    assert previous_month(date(2027, 1, 5)) == "202612"


def test_month_unpublished_last_time_is_asked_for_again():
    # 5 Nov: table ends at 2026-09, 2026-10 not out yet.
    assert months_to_collect("202609", date(2026, 11, 5)) == ["202610"]
    # 5 Dec: the old code asked for 2026-11 only and skipped 2026-10 for good.
    assert months_to_collect("202609", date(2026, 12, 5)) == ["202610", "202611"]


def test_nothing_to_do_when_table_is_current():
    assert months_to_collect("202610", date(2026, 11, 20)) == []


def test_empty_table_tries_only_last_month():
    assert months_to_collect(None, date(2026, 11, 5)) == ["202610"]


def test_catch_up_is_capped_and_oldest_first():
    months = months_to_collect("202401", date(2026, 11, 5), limit=12)
    assert months[0] == "202402"
    assert len(months) == 12


class _Engine:
    pass


def _run_scheduled(monkeypatch, latest, today, unpublished):
    calls = []

    def fake_load_month(engine, api_key, use_ym, *args):
        calls.append(use_ym)
        if use_ym in unpublished:
            raise MonthNotPublished(f"{use_ym} is not published yet")
        return 100

    class _Date(date):
        @classmethod
        def today(cls):
            return today

    monkeypatch.setattr(loader, "make_engine", lambda url: _Engine())
    monkeypatch.setattr(loader, "latest_loaded_month", lambda engine: latest)
    monkeypatch.setattr(loader, "load_month", fake_load_month)
    monkeypatch.setattr(loader, "date", _Date)
    monkeypatch.setattr(loader, "load_dotenv", lambda: None)
    monkeypatch.setenv("PIPELINE_DB_URL", "postgresql+psycopg2://u:p@127.0.0.1:1/x")
    monkeypatch.setenv("SEOUL_API_KEY", "test-key")
    monkeypatch.setattr(sys, "argv", ["load_hourly_boarding"])
    loader.main()
    return calls


def test_scheduled_run_stops_at_first_unpublished_month(monkeypatch, capsys):
    calls = _run_scheduled(monkeypatch, "202609", date(2026, 12, 5), {"202610"})
    # 2026-11 cannot be out before 2026-10, so it is not requested.
    assert calls == ["202610"]
    assert "stopping here" in capsys.readouterr().out


def test_scheduled_run_loads_every_published_month(monkeypatch, capsys):
    calls = _run_scheduled(monkeypatch, "202609", date(2026, 12, 5), set())
    assert calls == ["202610", "202611"]


def test_scheduled_run_with_nothing_new_does_not_call_api(monkeypatch, capsys):
    calls = _run_scheduled(monkeypatch, "202611", date(2026, 12, 5), set())
    assert calls == []
    assert "Up to date" in capsys.readouterr().out
