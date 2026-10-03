"""Contracts for the hourly loader's row merging.

The table key is (use_ym, route_no, stop_ars, hour) but the API returns one row
per stop *visit*, so a route passing the same stop twice arrives twice. The
upsert assigns rather than adds, which once kept only the last visit and
understated 2024-01 boarding by 3.6% with no error and no change in row count.

Merging therefore has to happen before the write, over the whole month rather
than per page, because the two visits can land on either side of a page
boundary. These tests lock both properties down.
"""

from __future__ import annotations

import pytest

from pipelines.hourly_od_estimation.load_hourly_boarding import (
    iter_merged_chunks,
    merge_records,
    month_range,
)


def make_rows(*, route_no: str, stop_ars: str, boarding: dict[int, float],
              use_ym: str = "202401", stop_id: str = "S1",
              route_name: str = "동대문01", stop_name: str = "회기역",
              reg_ymd: str = "20240201") -> list[dict]:
    """One unpivoted page fragment: 24 hourly rows for a single stop visit.
    """
    return [
        {
            "use_ym": use_ym,
            "route_no": route_no,
            "route_name": route_name,
            "stop_id": stop_id,
            "stop_ars": stop_ars,
            "stop_name": stop_name,
            "hour": hour,
            "boarding_passengers": boarding.get(hour, 0.0),
            "alighting_passengers": 0.0,
            "reg_ymd": reg_ymd,
        }
        for hour in range(24)
    ]


def totals(chunks) -> dict[tuple[str, int], float]:
    return {
        (row["stop_ars"], row["hour"]): row["boarding_passengers"]
        for chunk in chunks for row in chunk
    }


def test_two_visits_to_the_same_stop_are_summed_not_overwritten():
    buckets: dict = {}

    merge_records(make_rows(route_no="동대문01", stop_ars="01001", boarding={8: 97271.0}), buckets)
    merge_records(make_rows(route_no="동대문01", stop_ars="01001", boarding={8: 3389.0}), buckets)

    assert len(buckets) == 1
    assert buckets[("202401", "동대문01", "01001")]["boarding"][8] == pytest.approx(100660.0)


def test_a_collision_across_a_page_boundary_is_still_merged():
    """Merging per page would miss this; the loader merges the whole month.
    """
    buckets: dict = {}

    page_one = make_rows(route_no="146", stop_ars="07616", boarding={7: 10.0})
    page_two = make_rows(route_no="146", stop_ars="07616", boarding={7: 5.0, 18: 20.0})
    merge_records(page_one, buckets)
    # An unrelated key arrives in between, as it would on a real page.
    merge_records(make_rows(route_no="146", stop_ars="07617", boarding={7: 1.0}), buckets)
    merge_records(page_two, buckets)

    merged = totals(iter_merged_chunks(buckets, 1000))

    assert merged[("07616", 7)] == pytest.approx(15.0)
    assert merged[("07616", 18)] == pytest.approx(20.0)
    assert merged[("07617", 7)] == pytest.approx(1.0)


def test_merging_preserves_the_monthly_total():
    buckets: dict = {}
    pages = [
        make_rows(route_no="146", stop_ars="07616", boarding={7: 10.0, 8: 20.0}),
        make_rows(route_no="146", stop_ars="07616", boarding={8: 5.0}),
        make_rows(route_no="146", stop_ars="07617", boarding={9: 7.0}),
    ]
    source_total = sum(row["boarding_passengers"] for page in pages for row in page)

    for page in pages:
        merge_records(page, buckets)
    merged_total = sum(
        row["boarding_passengers"] for chunk in iter_merged_chunks(buckets, 1000) for row in chunk
    )

    assert merged_total == pytest.approx(source_total)


def test_every_key_expands_back_to_twenty_four_hours():
    buckets: dict = {}
    merge_records(make_rows(route_no="146", stop_ars="07616", boarding={7: 10.0}), buckets)
    merge_records(make_rows(route_no="146", stop_ars="07617", boarding={9: 1.0}), buckets)

    rows = [row for chunk in iter_merged_chunks(buckets, 1000) for row in chunk]

    assert len(rows) == 48
    assert sorted(row["hour"] for row in rows) == sorted(list(range(24)) * 2)


def test_chunking_splits_rows_without_losing_or_duplicating_any():
    buckets: dict = {}
    for index in range(3):
        merge_records(make_rows(route_no="146", stop_ars=f"0761{index}", boarding={7: 1.0}), buckets)

    chunks = list(iter_merged_chunks(buckets, 10))

    assert all(len(chunk) <= 10 for chunk in chunks)
    assert sum(len(chunk) for chunk in chunks) == 72


def test_descriptive_columns_come_from_the_first_row_seen_for_a_key():
    buckets: dict = {}
    merge_records(make_rows(route_no="146", stop_ars="07616", boarding={7: 1.0},
                            stop_name="회기역"), buckets)
    merge_records(make_rows(route_no="146", stop_ars="07616", boarding={7: 1.0},
                            stop_name="회기역.경희대"), buckets)

    rows = [row for chunk in iter_merged_chunks(buckets, 1000) for row in chunk]

    assert {row["stop_name"] for row in rows} == {"회기역"}


def test_month_range_is_inclusive_and_crosses_a_year_boundary():
    assert month_range("202411", "202502") == ["202411", "202412", "202501", "202502"]
    assert month_range("202401", "202401") == ["202401"]


@pytest.mark.parametrize("start_ym, end_ym", [
    ("2024", "202412"),
    ("202413", "202512"),
    ("202412", "202401"),
])
def test_month_range_refuses_malformed_or_reversed_input(start_ym, end_ym):
    with pytest.raises(ValueError):
        month_range(start_ym, end_ym)
