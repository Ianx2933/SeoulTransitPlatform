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

    assert table[0].startswith("| Hour | 0–4% |")
    assert [line.split("|")[1].strip() for line in table[2:]] == ["04", "05", "07", "08", "18", "22"]
    assert sum("**" in line for line in table[2:]) == 1
