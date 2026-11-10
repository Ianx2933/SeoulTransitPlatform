"""
Compare two corrected OD files row for row (as multisets: order is ignored).

    # the pandas port must reproduce the Java SQL exactly
    python pipelines/od_correction/src/compare_corrections.py ^
        data/od/java_baseline_20260915.csv data/od/faithful_20260915.csv --expect-equal

    # what fixing deduplicateAndSum changes
    python pipelines/od_correction/src/compare_corrections.py ^
        data/od/faithful_20260915.csv data/od/fixed_20260915.csv --left-name faithful --right-name fixed

Columns are matched by position, so the baseline's lower-case headers
(전환_노선id, 승차_정류장ars) compare against the port's. Values are compared as
text; an empty field is NULL on both sides.
"""

from __future__ import annotations

import argparse
from collections import Counter
from pathlib import Path

import pandas as pd

COLUMNS = [
    "기준일자", "노선명", "전환_노선ID", "승차_정류장순번", "승차_정류장ARS", "승차_정류장표준코드",
    "승차_정류장명", "하차_정류장순번", "하차_정류장ARS", "하차_정류장표준코드", "하차_정류장명", "승객수",
]
PAX = "승객수"


def read_text(path: Path) -> pd.DataFrame:
    frame = pd.read_csv(path, sep="|", encoding="utf-8-sig", dtype=str, keep_default_na=False)
    if len(frame.columns) != len(COLUMNS):
        raise SystemExit(f"{path}: {len(frame.columns)} columns, expected {len(COLUMNS)}")
    frame.columns = COLUMNS
    return frame


def summary(frame: pd.DataFrame) -> dict:
    pax = pd.to_numeric(frame[PAX].replace("", None))
    return {
        "rows": len(frame),
        "passengers": int(pax.sum()),
        "null_boarding_code": int(frame["승차_정류장표준코드"].eq("").sum()),
        "null_alighting_code": int(frame["하차_정류장표준코드"].eq("").sum()),
        "null_route_id": int(frame["전환_노선ID"].eq("").sum()),
    }


def compare(left: pd.DataFrame, right: pd.DataFrame) -> tuple[Counter, Counter]:
    a = Counter(map(tuple, left.itertuples(index=False, name=None)))
    b = Counter(map(tuple, right.itertuples(index=False, name=None)))
    return a - b, b - a


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Compare two corrected OD files row for row.")
    parser.add_argument("left", type=Path)
    parser.add_argument("right", type=Path)
    parser.add_argument("--left-name", default="left")
    parser.add_argument("--right-name", default="right")
    parser.add_argument("--expect-equal", action="store_true", help="Exit 1 unless identical")
    parser.add_argument("--show", type=int, default=10, help="Differing rows to print per side")
    args = parser.parse_args(argv)

    left, right = read_text(args.left), read_text(args.right)
    ls, rs = summary(left), summary(right)
    width = max(len(args.left_name), len(args.right_name), 6)
    print(f"{'':<22}{args.left_name:>{width + 14}}{args.right_name:>{width + 14}}{'diff':>16}")
    for key in ls:
        print(f"{key:<22}{ls[key]:>{width + 14},}{rs[key]:>{width + 14},}{rs[key] - ls[key]:>+16,}")

    only_left, only_right = compare(left, right)
    n_left, n_right = sum(only_left.values()), sum(only_right.values())
    print(f"\nrows only in {args.left_name}: {n_left:,}; only in {args.right_name}: {n_right:,}")
    for name, rows in ((args.left_name, only_left), (args.right_name, only_right)):
        for row, count in list(rows.items())[:args.show]:
            print(f"  {name} x{count}: " + "|".join(row))

    if args.expect_equal:
        if n_left or n_right:
            print("NOT EQUAL")
            return 1
        print("EQUAL")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
