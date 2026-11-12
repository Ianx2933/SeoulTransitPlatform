"""
One line per corrected OD day, from the report.json files od_correct_job.py wrote.

    gcloud storage rsync gs://seoul-transit-prod-pipeline/od-curated C:/tmp/od-reports ^
        --recursive --exclude=".*\\.csv\\.gz$|^_inputs/.*"
    python pipelines/od_correction/src/summarize_od_reports.py C:/tmp/od-reports --csv C:/tmp/od_reports.csv

Columns: passengers in, conservation diff, anomaly, ferry passengers, ferry
passengers at piers closed that day, rows without route ID, rows without a
boarding standard code, seconds.
"""

from __future__ import annotations

import argparse
import json
from datetime import datetime
from pathlib import Path

import pandas as pd


def summarize(folder: Path) -> pd.DataFrame:
    rows = []
    for path in sorted(folder.glob("2*/report.json")):
        r = json.loads(path.read_text(encoding="utf-8"))
        s, f, c = r["structural"], r["ferry"], r["codes"]
        day = datetime.strptime(r["day"], "%Y%m%d")
        rows.append({
            "day": r["day"],
            "dow": day.strftime("%a"),
            "passengers": s["passengers"]["input"],
            "diff": s["passenger_diff"],
            "anomaly": s["passengers"]["anomaly"],
            "ferry": f["ferry_passengers"],
            "ferry_at_closed": sum(d["passengers_at_closed_piers"] for d in f["by_date"]),
            "null_route_id": c["null_route_id"],
            "null_boarding_code": c["null_boarding_code"],
            "seconds": r["seconds"],
        })
    return pd.DataFrame(rows)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Summarize od-curated report.json files.")
    parser.add_argument("folder", type=Path)
    parser.add_argument("--csv", type=Path, default=None)
    args = parser.parse_args(argv)

    table = summarize(args.folder)
    pd.set_option("display.width", 200)
    pd.set_option("display.max_rows", 500)
    print(table.to_string(index=False, formatters={
        k: "{:,}".format for k in ("passengers", "anomaly", "ferry", "ferry_at_closed",
                                   "null_route_id", "null_boarding_code")}))
    weekday = table[~table["dow"].isin(["Sat", "Sun"])]["passengers"]
    print(f"\n{len(table)} days; nonzero diff: {int((table['diff'] != 0).sum())}; "
          f"weekday passengers {weekday.min():,} .. {weekday.max():,} (median {int(weekday.median()):,}); "
          f"ferry at closed piers on {int((table['ferry_at_closed'] > 0).sum())} days")
    if args.csv:
        table.to_csv(args.csv, index=False, encoding="utf-8-sig")


if __name__ == "__main__":
    main()
