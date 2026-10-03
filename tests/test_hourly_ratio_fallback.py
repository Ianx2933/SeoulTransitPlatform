"""Executable contracts for hourly OD profile precedence and passenger conservation.
"""

from __future__ import annotations

import pandas as pd
import pytest

from pipelines.hourly_od_estimation.estimate_hourly_od import (
    DEFAULT_RATIO,
    METHOD_FALLBACK_NO_KEY,
    METHOD_FALLBACK_ZERO_FIT,
    METHOD_FITTED,
    build_ratio_map,
    estimate,
    select_profile,
)


# 2025-11-11 is a Tuesday, so day_type is "tue" with an empty holiday set.
OD_DATE = "20251111"
OD_DAY_TYPE = "tue"


class RecordingConnection:
    """Stands in for an open SQLAlchemy connection and keeps the rows written.
    """

    def __init__(self) -> None:
        self.records: list[dict] = []

    def execute(self, statement, records):
        self.records.extend(records)


def make_ratio_frame(rows: list[tuple[str, str, str, list[float]]]) -> pd.DataFrame:
    """Expand (route, ars, day_type, 24 values) tuples into one row per hour.
    """
    records = []
    for route_no, stop_ars, day_type, values in rows:
        for hour, value in enumerate(values):
            records.append({
                "route_no": route_no,
                "stop_ars": stop_ars,
                "day_type": day_type,
                "hour": hour,
                "boarding_ratio": value,
            })
    return pd.DataFrame(records)


def flat_profile() -> list[float]:
    return [1 / 24] * 24


def peaked_profile() -> list[float]:
    values = [0.0] * 24
    values[8] = 0.6
    values[18] = 0.4
    return values


def make_daily_od(rows: list[tuple[str, str, str, float]]) -> pd.DataFrame:
    return pd.DataFrame(
        [
            {
                "기준일자": OD_DATE,
                "노선명": route_no,
                "승차_정류장ars": origin,
                "하차_정류장ars": destination,
                "daily_passengers": passengers,
            }
            for route_no, origin, destination, passengers in rows
        ]
    )


def run_estimate(daily_od: pd.DataFrame, ratio_map: dict, zero_keys: set) -> list[dict]:
    connection = RecordingConnection()
    written = estimate(
        engine=None,
        daily_od=daily_od,
        ratio_map=ratio_map,
        zero_keys=zero_keys,
        holidays=set(),
        batch_size=1000,
        conn=connection,
    )
    assert written == len(connection.records)
    return connection.records


def test_fallback_profile_distributes_exactly_one_day():
    assert len(DEFAULT_RATIO) == 24
    assert all(value >= 0 for value in DEFAULT_RATIO)
    assert sum(DEFAULT_RATIO) == pytest.approx(1.0)


def test_build_ratio_map_separates_profiles_that_cannot_be_applied():
    frame = make_ratio_frame([
        ("146", "07616", OD_DAY_TYPE, peaked_profile()),
        ("146", "07617", OD_DAY_TYPE, [0.0] * 24),
    ])

    ratio_map, zero_keys = build_ratio_map(frame)

    assert set(ratio_map) == {("146", "07616", OD_DAY_TYPE)}
    assert zero_keys == {("146", "07617", OD_DAY_TYPE)}


def test_build_ratio_map_normalizes_ars_codes_on_both_sides():
    frame = make_ratio_frame([("146", "7616", OD_DAY_TYPE, flat_profile())])

    ratio_map, zero_keys = build_ratio_map(frame)

    assert ("146", "07616", OD_DAY_TYPE) in ratio_map
    assert zero_keys == set()


def test_build_ratio_map_handles_an_empty_table():
    ratio_map, zero_keys = build_ratio_map(pd.DataFrame())

    assert ratio_map == {}
    assert zero_keys == set()


def test_profile_precedence_records_the_rule_that_applied():
    fitted_key = ("146", "07616", OD_DAY_TYPE)
    zero_key = ("146", "07617", OD_DAY_TYPE)
    missing_key = ("146", "07618", OD_DAY_TYPE)
    ratio_map = {fitted_key: peaked_profile()}
    zero_keys = {zero_key}

    assert select_profile(fitted_key, ratio_map, zero_keys) == (peaked_profile(), METHOD_FITTED)
    assert select_profile(zero_key, ratio_map, zero_keys) == (DEFAULT_RATIO, METHOD_FALLBACK_ZERO_FIT)
    assert select_profile(missing_key, ratio_map, zero_keys) == (DEFAULT_RATIO, METHOD_FALLBACK_NO_KEY)


def test_an_all_zero_profile_does_not_silently_drop_passengers():
    """The gap this fix closes: a key existed, summed to zero, and multiplied the day away.
    """
    daily_od = make_daily_od([("146", "07617", "01001", 500.0)])
    ratio_map, zero_keys = build_ratio_map(
        make_ratio_frame([("146", "07617", OD_DAY_TYPE, [0.0] * 24)])
    )

    records = run_estimate(daily_od, ratio_map, zero_keys)

    assert len(records) == 24
    assert sum(record["estimated_passengers"] for record in records) == pytest.approx(500.0)
    assert {record["method"] for record in records} == {METHOD_FALLBACK_ZERO_FIT}


@pytest.mark.parametrize(
    "origin_ars, expected_method",
    [
        ("07616", METHOD_FITTED),
        ("07617", METHOD_FALLBACK_ZERO_FIT),
        ("07618", METHOD_FALLBACK_NO_KEY),
    ],
)
def test_every_profile_path_conserves_daily_passengers(origin_ars, expected_method):
    daily_od = make_daily_od([("146", origin_ars, "01001", 1234.5)])
    ratio_map, zero_keys = build_ratio_map(make_ratio_frame([
        ("146", "07616", OD_DAY_TYPE, peaked_profile()),
        ("146", "07617", OD_DAY_TYPE, [0.0] * 24),
    ]))

    records = run_estimate(daily_od, ratio_map, zero_keys)

    assert len(records) == 24
    assert sum(record["estimated_passengers"] for record in records) == pytest.approx(1234.5)
    assert {record["method"] for record in records} == {expected_method}


def test_each_od_row_produces_exactly_twenty_four_rows():
    daily_od = make_daily_od([
        ("146", "07616", "01001", 100.0),
        ("146", "07617", "01002", 200.0),
        ("146", "07618", "01003", 300.0),
    ])
    ratio_map, zero_keys = build_ratio_map(make_ratio_frame([
        ("146", "07616", OD_DAY_TYPE, peaked_profile()),
        ("146", "07617", OD_DAY_TYPE, [0.0] * 24),
    ]))

    records = run_estimate(daily_od, ratio_map, zero_keys)

    assert len(records) == 24 * len(daily_od)
    assert sorted(record["hour"] for record in records) == sorted(list(range(24)) * 3)
    assert sum(record["estimated_passengers"] for record in records) == pytest.approx(600.0)


def test_a_profile_fitted_for_another_day_type_is_not_borrowed():
    """The key includes day_type, so a Saturday profile must not serve a Tuesday.
    """
    daily_od = make_daily_od([("146", "07616", "01001", 100.0)])
    ratio_map, zero_keys = build_ratio_map(
        make_ratio_frame([("146", "07616", "sat", peaked_profile())])
    )

    records = run_estimate(daily_od, ratio_map, zero_keys)

    assert {record["method"] for record in records} == {METHOD_FALLBACK_NO_KEY}
    assert sum(record["estimated_passengers"] for record in records) == pytest.approx(100.0)


def test_batching_does_not_change_what_is_written():
    daily_od = make_daily_od([("146", "07616", "01001", 100.0), ("146", "07618", "01002", 50.0)])
    ratio_map, zero_keys = build_ratio_map(
        make_ratio_frame([("146", "07616", OD_DAY_TYPE, peaked_profile())])
    )

    small = RecordingConnection()
    estimate(None, daily_od, ratio_map, zero_keys, set(), batch_size=7, conn=small)
    large = RecordingConnection()
    estimate(None, daily_od, ratio_map, zero_keys, set(), batch_size=10_000, conn=large)

    assert small.records == large.records


@pytest.mark.parametrize("mutation", ["half", "nan", "infinity", "negative", "duplicate", "missing", "outside", "fractional", "null_key", "normalized_collision"])
def test_build_ratio_map_rejects_corrupt_profiles(mutation):
    frame = make_ratio_frame([("146", "07616", OD_DAY_TYPE, flat_profile())])
    if mutation == "half":
        frame["boarding_ratio"] /= 2
    elif mutation in ("nan", "infinity", "negative"):
        frame.loc[0, "boarding_ratio"] = {"nan": float("nan"), "infinity": float("inf"), "negative": -1}[mutation]
    elif mutation == "duplicate":
        frame = pd.concat([frame, frame.iloc[:1]], ignore_index=True)
    elif mutation == "missing":
        frame = frame.iloc[:-1]
    elif mutation == "outside":
        frame.loc[0, "hour"] = 24
    elif mutation == "fractional":
        frame["hour"] = frame["hour"].astype(float)
        frame.loc[0, "hour"] = 0.5
    elif mutation == "null_key":
        frame.loc[0, "stop_ars"] = None
    else:
        other = frame.copy()
        other["stop_ars"] = "7616"
        frame = pd.concat([frame, other], ignore_index=True)
    with pytest.raises(ValueError):
        build_ratio_map(frame)


def test_near_one_profiles_are_normalized_before_allocation():
    frame = make_ratio_frame([("146", "07616", OD_DAY_TYPE, [1.0000005 / 24] * 24)])
    profiles, zero_keys = build_ratio_map(frame)
    assert sum(profiles[("146", "07616", OD_DAY_TYPE)]) == pytest.approx(1, abs=1e-14)
    assert not zero_keys
