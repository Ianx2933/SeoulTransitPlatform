"""Contracts for the xarray terrain profile.

The README publishes a slope-band table from this script, so the parts that decide
those numbers are pinned here against a plain pandas computation on synthetic data.
"""

import importlib.util
from pathlib import Path

import numpy as np
import pandas as pd
import pytest

pytest.importorskip("xarray")

PATH = Path(__file__).resolve().parents[1] / "pipelines" / "analysis" / "demand_xarray.py"
spec = importlib.util.spec_from_file_location("demand_xarray", PATH)
dx = importlib.util.module_from_spec(spec)
spec.loader.exec_module(dx)

STOPS = {            # stop -> slope_pct; None = no terrain match
    "01001": 0.0,    # flat: must land in 0-4%, not in no band
    "01002": 4.0,    # upper edge of 0-4%
    "01003": 4.01,
    "01004": 9.5,
    "01005": 12.0,   # upper edge of 8-12%
    "01006": 30.0,
    "01007": None,
}


def synthetic():
    rng = np.random.default_rng(5)
    rows = []
    for ym in ["202401", "202402", "202403"]:
        for hour in range(24):
            for stop in STOPS:
                if rng.random() < 0.2:      # a stop-hour with no service that month
                    continue
                rows.append({"use_ym": ym, "hour": hour, "stop_ars": stop,
                             "boardings": int(rng.integers(0, 900)),
                             "alightings": int(rng.integers(0, 900))})
    demand = pd.DataFrame(rows)
    slope = pd.DataFrame([{"stop_ars": s, "slope_pct": v, "elev_m": 50.0}
                          for s, v in STOPS.items() if v is not None])
    return demand, slope


def test_bands_include_flat_ground_and_close_on_the_upper_edge():
    labels = dx.slope_bands([0.0, 4.0, 4.01, 8.0, 12.0, 12.5, 150.0, np.nan])
    assert labels.tolist() == ["0-4%", "0-4%", "4-8%", "4-8%", "8-12%", "12%+", "12%+",
                               dx.NO_TERRAIN]


def test_missing_stop_hours_stay_missing_and_totals_reconcile():
    demand, slope = synthetic()
    ds = dx.build_dataset(demand, slope)

    assert dict(ds.sizes) == {"month": 3, "hour": 24, "stop_ars": len(STOPS)}
    assert int(ds.boardings.notnull().sum()) == len(demand)
    assert dx.reconcile(ds, demand["boardings"].sum()) == demand["boardings"].sum()
    with pytest.raises(ValueError, match="mismatch"):
        dx.reconcile(ds, demand["boardings"].sum() + 1)


def test_a_stop_without_terrain_is_kept_but_in_no_band():
    demand, slope = synthetic()
    ds = dx.build_dataset(demand, slope)

    unmatched = ds.sel(stop_ars="01007")
    assert np.isnan(float(unmatched.slope_pct))
    assert unmatched.slope_band.item() == dx.NO_TERRAIN
    assert dx.stops_per_band(ds).to_dict() == {"0-4%": 2, "4-8%": 1, "8-12%": 2, "12%+": 1,
                                               dx.NO_TERRAIN: 1}


def test_band_profile_matches_a_plain_pandas_computation():
    demand, slope = synthetic()
    profile = dx.band_profile(dx.build_dataset(demand, slope))

    band = dict(zip(slope["stop_ars"], dx.slope_bands(slope["slope_pct"])))
    per_stop_hour = demand.groupby(["stop_ars", "hour"])["boardings"].mean().reset_index()
    per_stop_hour["band"] = per_stop_hour["stop_ars"].map(band)
    expected = (per_stop_hour.dropna(subset=["band"])
                .groupby(["hour", "band"])["boardings"].mean().unstack("band")[dx.BAND_LABELS])

    assert list(profile.dims) == ["hour", "slope_band"]
    np.testing.assert_allclose(profile.to_pandas().to_numpy(), expected.to_numpy())


def test_band_shapes_sum_to_one():
    demand, slope = synthetic()
    shape = dx.band_shape(dx.band_profile(dx.build_dataset(demand, slope)))
    np.testing.assert_allclose(shape.sum(dim="hour").values, 1.0)


def test_readme_table_has_the_published_hours_and_marks_the_lowest_share():
    demand, slope = synthetic()
    table = dx.readme_table(dx.band_profile(dx.build_dataset(demand, slope))).splitlines()

    assert table[0].startswith("| Hour | 0-4% |")
    assert table[0].isascii()
    assert [line.split("|")[1].strip() for line in table[2:]] == ["04", "05", "07", "08", "18", "22"]
    assert sum("**" in line for line in table[2:]) == 1


def test_demand_is_read_one_month_at_a_time_and_totals_are_counted_separately(monkeypatch, capsys):
    demand, _ = synthetic()
    calls = []

    def fake_read_sql(sql, engine, params=None):
        calls.append((sql, params))
        month = demand[demand["use_ym"] == params["use_ym"]]
        if sql is dx.TOTAL_SQL:
            return pd.DataFrame({"t": [int(month["boardings"].sum())]})
        return month.reset_index(drop=True)

    monkeypatch.setattr(dx.pd, "read_sql", fake_read_sql)
    rows, expected = dx.load_demand(None, ["202401", "202402", "202403"])

    assert [p["use_ym"] for _, p in calls] == ["202401", "202401", "202402", "202402", "202403", "202403"]
    assert len(rows) == len(demand) and expected == demand["boardings"].sum()
    assert "WHERE use_ym = %(use_ym)s" in dx.DEMAND_SQL and "WHERE use_ym" in dx.TOTAL_SQL


def test_a_dropped_month_is_read_again_and_earlier_months_are_kept(monkeypatch, capsys):
    from sqlalchemy.exc import OperationalError

    demand, _ = synthetic()
    drops = [1]

    def flaky_read_sql(sql, engine, params=None):
        if params["use_ym"] == "202402" and sql is dx.DEMAND_SQL and drops:
            drops.pop()
            raise OperationalError("SELECT ...", {}, Exception("server closed the connection"))
        month = demand[demand["use_ym"] == params["use_ym"]]
        if sql is dx.TOTAL_SQL:
            return pd.DataFrame({"t": [int(month["boardings"].sum())]})
        return month.reset_index(drop=True)

    monkeypatch.setattr(dx.pd, "read_sql", flaky_read_sql)
    waits = []
    rows, expected = dx.load_demand(None, ["202401", "202402", "202403"], sleep=waits.append)

    assert waits == [15]
    assert len(rows) == len(demand) and expected == demand["boardings"].sum()
    assert "202402: connection lost" in capsys.readouterr().out


def test_bigquery_source_returns_the_same_shapes_as_postgres():
    demand, slope = synthetic()
    total = int(demand["boardings"].sum())
    seen = []

    def query(sql):
        seen.append(sql)
        if "fct_boarding" in sql:
            frame = demand.copy()
            frame["use_ym"] = frame["use_ym"].astype(int).astype(str)   # BigQuery casts back to STRING
            return frame
        if "dim_stop" in sql:
            return slope.copy()
        return pd.DataFrame({"t": [total]})

    got_demand, got_slope, expected = dx.load_bigquery("p", query=query)

    assert any("`p.transit_marts.fct_boarding`" in q for q in seen)
    assert any("`p.transit_staging.hourly_bus_stop_passenger`" in q for q in seen)
    assert expected == total
    ds = dx.build_dataset(got_demand, got_slope)
    assert dx.reconcile(ds, expected) == total
