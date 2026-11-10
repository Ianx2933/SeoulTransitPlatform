"""
Han River ferry (한강버스) rows in the TOPIS route OD.

The ferry appears in the route-level OD with no route name, ARS or sequence:
only pier names and passengers. Left like that it cannot be told apart from bus
rows missing their route, and every pier collapses onto the same empty key.
This step runs after od_correction_pipeline.py and before
od_structural_correction.py (fixed). It

  * recognises ferry rows: no route, no ARS on either side, boarding name a pier;
  * gives them the ferry route name and the pier's position on the river,
    west to east, as both sequences (the numbering the operator used too);
  * sends rows with no alighting pier to the next pier (position + 1; from
    잠실, 뚝섬);
  * treats a trip boarding and alighting at the same pier as boarding at the
    previous pier (position - 1; for 마곡, 망원), which is how the operator's
    fare data records it. Direction is not in the data, so both are
    conventions, not reconstructions;
  * checks the date against the operator's suspension calendar and reports
    passengers recorded at piers that were closed all day (a warning: the OD
    may come from a different day or the calendar may be incomplete);
  * counts trips between the western piers (마곡, 망원) and the eastern ones
    (압구정 .. 잠실) on dates when the two were separate lines. They are
    changes between ferry lines at 여의도, kept as ferry OD and counted apart
    from transfers to other modes.

Partial cancellations (some sailings only) are not in the calendar: every pier
still had service that day.

    python pipelines/od_correction/src/ferry_od.py --input codes_20260915.csv --output codes_ferry_20260915.csv
"""

from __future__ import annotations

import argparse
import json
from datetime import date
from pathlib import Path

import pandas as pd

import od_structural_correction as sc

PIERS = ["마곡", "망원", "여의도", "압구정", "옥수", "성수", "뚝섬", "잠실"]
PIER_ALIASES = {"서울숲": "성수"}
POSITION = {name: i + 1 for i, name in enumerate(PIERS)}
WEST = {"마곡", "망원"}
EAST = {"압구정", "옥수", "성수", "뚝섬", "잠실"}
SPLIT_FROM = date(2026, 3, 1)          # 마곡–여의도 and 여의도–잠실 run as separate lines
DEFAULT_ROUTE_NAME = "한강버스"
CALENDAR = Path(__file__).resolve().parents[1] / "reference" / "hangang_ferry_suspensions.csv"


def parse_day(value: str) -> date:
    value = str(value)
    if len(value) == 6:
        return date(2000 + int(value[:2]), int(value[2:4]), int(value[4:]))
    return date(int(value[:4]), int(value[4:6]), int(value[6:]))


def load_calendar(path: Path = CALENDAR) -> list[dict]:
    frame = pd.read_csv(path, sep="|", encoding="utf-8", dtype=str)
    out = []
    for row in frame.itertuples(index=False):
        piers = set(PIERS) if row.suspended_piers == "all" else set(row.suspended_piers.split(","))
        unknown = piers - set(PIERS)
        if unknown:
            raise ValueError(f"Unknown pier in {path}: {sorted(unknown)}")
        out.append({"start": date.fromisoformat(row.start), "end": date.fromisoformat(row.end),
                    "piers": piers, "note": row.note})
    return out


def closed_piers(day: date, calendar: list[dict]) -> set[str]:
    closed: set[str] = set()
    for entry in calendar:
        if entry["start"] <= day <= entry["end"]:
            closed |= entry["piers"]
    return closed


def next_pier(name: str) -> str:
    position = POSITION[name]
    return PIERS[position] if position < len(PIERS) else PIERS[position - 2]


def previous_pier(name: str) -> str:
    position = POSITION[name]
    return PIERS[position - 2] if position > 1 else PIERS[1]


def normalize_ferry(frame: pd.DataFrame, route_name: str = DEFAULT_ROUTE_NAME,
                    calendar: list[dict] | None = None) -> tuple[pd.DataFrame, dict]:
    calendar = load_calendar() if calendar is None else calendar
    d = sc.normalize_types(frame)
    for column in (sc.B_NAME, sc.A_NAME):
        d[column] = d[column].replace(PIER_ALIASES)

    ferry = (d[sc.ROUTE].isna() & d[sc.B_ARS].isna() & d[sc.A_ARS].isna()
             & d[sc.B_NAME].isin(PIERS).fillna(False)
             & (d[sc.A_NAME].isna() | d[sc.A_NAME].isin(PIERS).fillna(False)))
    other_no_route = d[sc.ROUTE].isna() & ~ferry

    no_alighting = ferry & d[sc.A_NAME].isna()
    same_pier = ferry & sc.eq(d[sc.B_NAME], d[sc.A_NAME])
    d.loc[no_alighting, sc.A_NAME] = d.loc[no_alighting, sc.B_NAME].map(next_pier).astype("string")
    d.loc[same_pier, sc.B_NAME] = d.loc[same_pier, sc.A_NAME].map(previous_pier).astype("string")

    d.loc[ferry, sc.ROUTE] = route_name
    d.loc[ferry, sc.B_SEQ] = d.loc[ferry, sc.B_NAME].map(POSITION).astype("Int64")
    d.loc[ferry, sc.A_SEQ] = d.loc[ferry, sc.A_NAME].map(POSITION).astype("Int64")

    by_date = []
    for value, rows in d[ferry].groupby(sc.DATE):
        day = parse_day(value)
        closed = closed_piers(day, calendar)
        at_closed = rows[rows[sc.B_NAME].isin(closed) | rows[sc.A_NAME].isin(closed)]
        cross = rows[(rows[sc.B_NAME].isin(WEST) & rows[sc.A_NAME].isin(EAST))
                     | (rows[sc.B_NAME].isin(EAST) & rows[sc.A_NAME].isin(WEST))]
        by_date.append({
            "date": day.isoformat(),
            "rows": len(rows),
            "passengers": int(rows[sc.PAX].sum()),
            "closed_piers": sorted(closed, key=POSITION.get),
            "passengers_at_closed_piers": int(at_closed[sc.PAX].sum()),
            "ferry_line_transfer_passengers": int(cross[sc.PAX].sum()) if day >= SPLIT_FROM else None,
        })

    report = {
        "route_name": route_name,
        "ferry_rows": int(ferry.sum()),
        "ferry_passengers": int(d.loc[ferry, sc.PAX].sum()),
        "no_alighting_to_next_pier": {"rows": int(no_alighting.sum()),
                                      "passengers": int(d.loc[no_alighting, sc.PAX].sum())},
        "same_pier_boarding_to_previous": {"rows": int(same_pier.sum()),
                                           "passengers": int(d.loc[same_pier, sc.PAX].sum())},
        "other_rows_without_route": {"rows": int(other_no_route.sum()),
                                     "passengers": int(d.loc[other_no_route, sc.PAX].sum())},
        "by_date": by_date,
    }
    return d, report


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Give Han River ferry OD rows a route and pier positions.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--route-name", default=DEFAULT_ROUTE_NAME)
    parser.add_argument("--report", type=Path, default=None)
    args = parser.parse_args(argv)

    out, report = normalize_ferry(sc.read_od(args.input), args.route_name)
    sc.write_od(out, args.output)
    print(json.dumps(report, ensure_ascii=False, indent=2))
    for entry in report["by_date"]:
        if entry["passengers_at_closed_piers"]:
            print(f"WARNING {entry['date']}: {entry['passengers_at_closed_piers']:,} passengers at piers "
                  f"closed all day ({', '.join(entry['closed_piers'])})")
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
