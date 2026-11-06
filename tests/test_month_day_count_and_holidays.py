"""Month counts default to the months in the table, and dates outside the holiday list fail.

Without a holiday list the day-type split does not fail: holidays are counted as
ordinary weekdays. The list shipped with the repository ends in 2026, so the
first 2027 month would have been classified wrongly without any error.
"""

import sys

import pytest

from pipelines.hourly_od_estimation import build_month_day_count as mdc
from pipelines.hourly_od_estimation.common import (
    DEFAULT_HOLIDAY_CSV,
    holiday_years,
    load_holidays,
    require_holiday_coverage,
)


def test_bundled_holiday_list_exists_and_covers_the_data_years():
    assert DEFAULT_HOLIDAY_CSV.exists()
    years = holiday_years(load_holidays(str(DEFAULT_HOLIDAY_CSV)))
    assert set(range(2023, 2027)) <= years


def test_year_outside_the_list_fails():
    holidays = {"2026-01-01", "2025-01-01"}
    require_holiday_coverage(holidays, {"2025", "2026"})
    with pytest.raises(SystemExit, match="2027"):
        require_holiday_coverage(holidays, {"2026", "2027"})


def test_empty_list_fails():
    with pytest.raises(SystemExit, match="covers none"):
        require_holiday_coverage(set(), {"2026"})


def run(monkeypatch, argv, loaded, holidays):
    written = []
    monkeypatch.setattr(sys, "argv", ["build_month_day_count", *argv])
    monkeypatch.setenv("PIPELINE_DB_URL", "postgresql+psycopg2://u:p@127.0.0.1:1/x")
    monkeypatch.setattr(mdc, "make_engine", lambda url: object())
    monkeypatch.setattr(mdc, "loaded_month_range", lambda engine: loaded)
    monkeypatch.setattr(mdc, "load_holidays", lambda path: holidays)
    monkeypatch.setattr(mdc, "upsert_month_count", lambda engine, ym, counts: written.append(ym))
    mdc.main()
    return written


ALL_YEARS = {f"{y}-01-01" for y in range(2020, 2027)}


def test_no_arguments_uses_every_month_in_the_table(monkeypatch, capsys):
    written = run(monkeypatch, [], ("202401", "202610"), ALL_YEARS)
    assert written[0] == "202401" and written[-1] == "202610"
    assert len(written) == 34


def test_explicit_range_still_works(monkeypatch, capsys):
    assert run(monkeypatch, ["--start-ym", "202501", "--end-ym", "202503"], None, ALL_YEARS) == [
        "202501", "202502", "202503",
    ]


def test_half_a_range_is_rejected(monkeypatch, capsys):
    with pytest.raises(SystemExit):
        run(monkeypatch, ["--start-ym", "202501"], None, ALL_YEARS)


def test_month_beyond_the_holiday_list_writes_nothing(monkeypatch, capsys):
    with pytest.raises(SystemExit, match="2027"):
        run(monkeypatch, [], ("202612", "202701"), ALL_YEARS)


def test_empty_table_is_an_error(monkeypatch, capsys):
    with pytest.raises(SystemExit, match="empty"):
        run(monkeypatch, [], None, ALL_YEARS)
