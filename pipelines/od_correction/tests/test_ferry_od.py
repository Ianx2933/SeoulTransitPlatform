"""Han River ferry rows: route, pier positions, missing and same-pier alighting.

The fixture is the 61 ferry rows of 2026-09-15 as they arrive from TOPIS: no
route, ARS or sequence.
"""

import sys
from datetime import date
from pathlib import Path

import pandas as pd

SRC = Path(__file__).resolve().parents[1] / "src"
sys.path.insert(0, str(SRC))

import ferry_od as ferry  # noqa: E402
import od_structural_correction as sc  # noqa: E402

RAW_20260915 = """뚝섬,마곡,6;뚝섬,망원,1;뚝섬,성수,19;뚝섬,압구정,5;뚝섬,여의도,204;뚝섬,옥수,5;뚝섬,잠실,107;뚝섬,,13;
마곡,뚝섬,2;마곡,망원,49;마곡,성수,5;마곡,압구정,3;마곡,여의도,107;마곡,옥수,1;마곡,잠실,20;마곡,,8;
망원,뚝섬,9;망원,마곡,54;망원,망원,3;망원,성수,15;망원,여의도,129;망원,옥수,1;망원,잠실,4;망원,,2;
성수,뚝섬,19;성수,망원,3;성수,성수,3;성수,압구정,14;성수,여의도,147;성수,옥수,2;성수,잠실,62;성수,,8;
압구정,뚝섬,12;압구정,마곡,5;압구정,망원,3;압구정,성수,16;압구정,여의도,47;압구정,잠실,22;
여의도,뚝섬,110;여의도,마곡,121;여의도,망원,116;여의도,성수,128;여의도,압구정,66;여의도,여의도,2;여의도,옥수,56;
여의도,잠실,185;여의도,,12;옥수,뚝섬,18;옥수,마곡,7;옥수,성수,10;옥수,여의도,40;옥수,잠실,27;옥수,,1;
잠실,뚝섬,34;잠실,마곡,29;잠실,성수,74;잠실,압구정,18;잠실,여의도,237;잠실,옥수,8;잠실,잠실,1;잠실,,9"""


def raw_ferry(date_value="260915", extra=()):
    rows = []
    for item in RAW_20260915.replace("\n", "").strip(";").split(";"):
        board, alight, pax = item.split(",")
        rows.append([date_value, None, None, None, None, None, board, None, None, None, alight or None, int(pax)])
    rows += list(extra)
    return sc.normalize_types(pd.DataFrame(rows, columns=sc.COLUMNS))


def bus_row(route="100", name="A", pax=5):
    return ["260915", route, "R1", 1, "01001", "100000001", name, 2, "01002", "100000002", "B", pax]


def test_the_61_rows_get_route_and_positions_and_keep_every_passenger():
    out, report = ferry.normalize_ferry(raw_ferry())
    assert report["ferry_rows"] == 61 and report["ferry_passengers"] == 2444
    assert (out[sc.ROUTE] == "한강버스01").all()
    assert out[sc.B_SEQ].notna().all() and out[sc.A_SEQ].notna().all()
    assert int(out[sc.PAX].sum()) == 2444


def test_missing_alighting_goes_to_the_next_pier_and_jamsil_to_ttukseom():
    out, report = ferry.normalize_ferry(raw_ferry())
    assert report["no_alighting_to_next_pier"] == {"rows": 7, "passengers": 53}
    # each pair: existing + moved from "no alighting" (+ same-pier rows that now board here)
    pairs = out.groupby([sc.B_NAME, sc.A_NAME])[sc.PAX].sum()
    assert pairs[("마곡", "망원")] == 49 + 8 + 3          # + 망원 -> 망원
    assert pairs[("잠실", "뚝섬")] == 34 + 9
    assert pairs[("여의도", "압구정")] == 66 + 12
    assert pairs[("뚝섬", "잠실")] == 107 + 13 + 1         # + 잠실 -> 잠실


def test_same_pier_trips_board_at_the_previous_pier():
    out, report = ferry.normalize_ferry(raw_ferry())
    assert report["same_pier_boarding_to_previous"] == {"rows": 4, "passengers": 9}
    pairs = out.groupby([sc.B_NAME, sc.A_NAME])[sc.PAX].sum()
    assert ("망원", "망원") not in pairs.index
    assert pairs[("마곡", "망원")] == 49 + 8 + 3          # 망원 -> 망원 boards at 마곡
    assert pairs[("옥수", "성수")] == 10 + 3 + 1          # 성수 -> 성수 boards at 옥수, + 옥수 -> NaN
    assert pairs[("망원", "여의도")] == 129 + 2 + 2       # 여의도 -> 여의도 boards at 망원, + 망원 -> NaN
    assert pairs[("뚝섬", "잠실")] == 107 + 13 + 1         # 잠실 -> 잠실 boards at 뚝섬
    assert ferry.previous_pier("마곡") == "망원"


def test_fixed_correction_merges_them_without_touching_the_bus_rows():
    with_bus = raw_ferry(extra=[bus_row(pax=5), bus_row(pax=6)])
    normalized, _ = ferry.normalize_ferry(with_bus)
    out, _, report = sc.correct(normalized, "fixed")
    assert report["passenger_diff"] == 0
    ferry_out = out[out[sc.ROUTE] == "한강버스01"]
    assert len(ferry_out) == ferry_out.groupby([sc.B_NAME, sc.A_NAME]).ngroups   # one row per pier pair
    assert int(ferry_out[sc.PAX].sum()) == 2444
    assert int(out.loc[out[sc.ROUTE] == "100", sc.PAX].sum()) == 11


def test_line_transfers_are_counted_from_the_split():
    _, report = ferry.normalize_ferry(raw_ferry())
    day = report["by_date"][0]
    # 마곡/망원 -> 압구정..잠실: 2+5+3+1+20 + 9+15+1+4 = 60
    # 압구정..잠실 -> 마곡/망원: 6+1 (뚝섬) + 3 (성수) + 5+3 (압구정) + 7 (옥수) + 29 (잠실) = 54
    assert day["ferry_line_transfer_passengers"] == 114
    _, before = ferry.normalize_ferry(raw_ferry("251111"))
    assert before["by_date"][0]["ferry_line_transfer_passengers"] is None


def test_closed_piers_are_reported():
    _, report = ferry.normalize_ferry(raw_ferry("251215"))   # east section closed 2025-11-16..2026-02-28
    day = report["by_date"][0]
    assert day["closed_piers"] == ["압구정", "옥수", "성수", "뚝섬", "잠실"]
    assert day["passengers_at_closed_piers"] > 0
    _, open_day = ferry.normalize_ferry(raw_ferry())
    assert open_day["by_date"][0]["passengers_at_closed_piers"] == 0


def test_rows_without_route_that_are_not_piers_are_left_alone():
    stray = ["260915", None, None, None, None, None, "어딘가", None, None, None, "여의도", 3]
    out, report = ferry.normalize_ferry(raw_ferry(extra=[stray]))
    assert report["other_rows_without_route"] == {"rows": 1, "passengers": 3}
    assert out.loc[out[sc.B_NAME] == "어딘가", sc.ROUTE].isna().all()


def test_calendar_parses_and_names_only_known_piers():
    calendar = ferry.load_calendar()
    assert ferry.closed_piers(date(2025, 10, 15), calendar) == set(ferry.PIERS)
    assert ferry.closed_piers(date(2026, 9, 22), calendar) == {"성수"}
    assert ferry.closed_piers(date(2026, 9, 15), calendar) == set()
