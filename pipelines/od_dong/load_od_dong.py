"""Load the typical-day district OD exported from BigQuery into Cloud SQL.

transit-bigquery's od/od_dong_export.py writes

    gs://seoul-transit-prod-pipeline/od-dong/<YYYYMMDD>/part-*.csv.gz
    gs://seoul-transit-prod-pipeline/od-dong/<YYYYMMDD>/manifest.json   (last)

This job takes the newest folder with a manifest (or --folder), checks rows and
rides per day type against the manifest, writes od_dong_typical__next with COPY,
checks it again in the database and swaps it in with publish_rebuild.publish, so
the district OD panel never reads a half-loaded table. It replaces the one-off
gcloud sql import of ops/od_dong/load_od_dong.md (an admin operation that blocked
other Cloud SQL work while it ran).

The schema is database/schema/07_od_dong_typical.sql; the copy next to this file
is what the job runs (tests/test_load_od_dong.py checks the two are identical).

    python -m pipelines.od_dong.load_od_dong
    python -m pipelines.od_dong.load_od_dong --dry-run
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import sys
from pathlib import Path

import pandas as pd
from sqlalchemy import text

from pipelines.hourly_od_estimation.common import copy_frame, make_engine, prepare_next_table
from pipelines.hourly_od_estimation.publish_rebuild import publish
from pipelines.od_typical.load_typical_od import GcsSource, pick_folder

TABLE = "od_dong_typical"
DEFAULT_URI = os.environ.get("OD_DONG_URI", "gs://seoul-transit-prod-pipeline/od-dong")
SCHEMA_SQL = Path(__file__).resolve().with_name("od_dong_typical.sql")
DAY_TYPES = ("weekday", "saturday", "sunday_holiday")
DB_COLUMNS = ["day_type", "origin_cd", "origin_name", "dest_cd", "dest_name",
              "subway_rides", "bus_rides", "total_rides"]
NUMBERS = ["subway_rides", "bus_rides", "total_rides"]
KEY = ["day_type", "origin_cd", "dest_cd"]
TOLERANCE = 1e-6   # relative, on ride sums of real numbers


def close(got: float, want: float) -> bool:
    return abs(got - want) <= TOLERANCE * max(1.0, abs(want))


def read_export(source, folder: str) -> tuple[pd.DataFrame, dict]:
    """Every day type of one export, checked against its manifest."""
    manifest = json.loads(source.read(f"{folder}/manifest.json"))
    missing = [d for d in DAY_TYPES if d not in manifest["day_types"]]
    if missing:
        raise ValueError(f"{folder}: export has no {', '.join(missing)}")
    frames = []
    for part in manifest["parts"]:
        data = source.read(f"{folder}/{part}")
        if part.endswith(".gz"):
            data = gzip.decompress(data)
        frames.append(pd.read_csv(io.BytesIO(data), dtype=str, keep_default_na=False, na_values=[""]))
    frame = pd.concat(frames, ignore_index=True)
    if list(frame.columns) != manifest["columns"] or sorted(frame.columns) != sorted(DB_COLUMNS):
        raise ValueError(f"{folder}: columns {list(frame.columns)} differ from the manifest or the table")
    for col in NUMBERS:
        frame[col] = pd.to_numeric(frame[col]).fillna(0.0)

    extra = sorted(set(frame["day_type"]) - set(DAY_TYPES))
    if extra:
        raise ValueError(f"{folder}: unknown day types {extra}")
    for day_type, want in manifest["day_types"].items():
        part = frame[frame["day_type"] == day_type]
        rides = float(part["total_rides"].sum())
        if len(part) != want["rows"] or not close(rides, want["total_rides"]):
            raise ValueError(f"{folder} {day_type}: files hold {len(part):,} rows / {rides:,.3f}, "
                             f"manifest says {want['rows']:,} / {want['total_rides']:,.3f}")
    dup = frame.duplicated(KEY)
    if dup.any():
        raise ValueError(f"{folder}: {int(dup.sum())} rows repeat a key, e.g. {frame[dup][KEY].iloc[0].tolist()}")
    for col in KEY:
        if frame[col].isna().any():
            raise ValueError(f"{folder}: empty {col}")
    return frame[DB_COLUMNS], manifest


def db_stats(conn, table: str) -> dict[str, tuple[int, float]]:
    rows = conn.execute(text(f"SELECT day_type, COUNT(*) AS n, SUM(total_rides) AS r FROM {table} GROUP BY day_type"))
    return {r.day_type: (int(r.n), float(r.r)) for r in rows}


def problems_against(manifest: dict, got: dict[str, tuple[int, float]]) -> list[str]:
    """What differs between the loaded table and the manifest (empty when they agree)."""
    out = []
    for day_type, want in manifest["day_types"].items():
        n, r = got.get(day_type, (0, 0.0))
        if n != want["rows"] or not close(r, want["total_rides"]):
            out.append(f"{day_type}: {n:,} rows / {r:,.3f} loaded, manifest {want['rows']:,} / {want['total_rides']:,.3f}")
    extra = sorted(set(got) - set(manifest["day_types"]))
    if extra:
        out.append(f"unexpected day types: {extra}")
    return out


def load(engine, frame: pd.DataFrame, manifest: dict) -> bool:
    """Swap the export in; False (nothing written) when the table already holds it."""
    with engine.begin() as conn:
        conn.exec_driver_sql(SCHEMA_SQL.read_text(encoding="utf-8"))
        if not problems_against(manifest, db_stats(conn, TABLE)):
            return False
        nxt = prepare_next_table(conn, TABLE)
        copy_frame(conn, frame, nxt, DB_COLUMNS)
        problems = problems_against(manifest, db_stats(conn, nxt))
        if problems:
            raise ValueError("Not published:\n  " + "\n  ".join(problems))
    publish(engine, [TABLE])
    return True


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--uri", default=DEFAULT_URI)
    p.add_argument("--folder", help="YYYYMMDD; default the newest complete export")
    p.add_argument("--db-url", default=os.environ.get("PIPELINE_DB_URL"))
    p.add_argument("--dry-run", action="store_true", help="read and check the export, write nothing")
    args = p.parse_args(argv)

    source = GcsSource(args.uri)
    folder = pick_folder(source, args.folder)
    frame, manifest = read_export(source, folder)
    summary = ", ".join(f"{d} {v['rows']:,} rows / {v['total_rides']:,.1f}" for d, v in manifest["day_types"].items())
    print(f"od-dong/{folder}: {summary}", flush=True)
    if args.dry_run:
        return 0
    if not args.db_url:
        raise SystemExit("PIPELINE_DB_URL or --db-url is required")
    if load(make_engine(args.db_url), frame, manifest):
        print(f"LOADED {TABLE}: {len(frame):,} rows from od-dong/{folder}", flush=True)
    else:
        print(f"{TABLE} already holds od-dong/{folder}; nothing to do.", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
