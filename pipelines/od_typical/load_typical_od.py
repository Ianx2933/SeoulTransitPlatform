"""Load the typical-day OD exported from BigQuery into Cloud SQL.

transit-bigquery's od/od_typical_export.py writes

    gs://seoul-transit-prod-pipeline/od-typical/<YYYYMMDD>/part-*.csv.gz
    gs://seoul-transit-prod-pipeline/od-typical/<YYYYMMDD>/manifest.json   (last)

where YYYYMMDD is the last OD day in the twelve-month window. This job takes the
newest folder with a manifest (or --folder), keeps the requested day types, checks
rows and passengers per day type against the manifest, writes
od_typical_route_stop__next with COPY, checks it again in the database, and swaps
it in with publish_rebuild.publish: the API never sees a half-loaded table.

The schema is database/schema/09_od_typical_route_stop.sql; the table is created
from it on the first run.

    python -m pipelines.od_typical.load_typical_od --day-types weekday
    python -m pipelines.od_typical.load_typical_od --day-types weekday,saturday,sunday_holiday
    python -m pipelines.od_typical.load_typical_od --dry-run
"""
from __future__ import annotations

import argparse
import gzip
import io
import json
import os
import re
import sys
from pathlib import Path

import pandas as pd
from sqlalchemy import text

from pipelines.hourly_od_estimation.common import copy_frame, make_engine, prepare_next_table
from pipelines.hourly_od_estimation.publish_rebuild import publish

TABLE = "od_typical_route_stop"
DEFAULT_URI = os.environ.get("OD_TYPICAL_URI", "gs://seoul-transit-prod-pipeline/od-typical")
SCHEMA_SQL = Path(__file__).resolve().parents[2] / "database" / "schema" / "09_od_typical_route_stop.sql"
DAY_TYPES = ("weekday", "saturday", "sunday_holiday")
FOLDER = re.compile(r"^(\d{8})/manifest\.json$")
RENAME = {
    "label": "기준일자",
    "route_name": "노선명",
    "route_id": "전환_노선id",
    "board_seq": "승차_정류장순번",
    "board_key": "승차_정류장ars",
    "board_code": "승차_정류장표준코드",
    "board_name": "승차_정류장명",
    "alight_seq": "하차_정류장순번",
    "alight_key": "하차_정류장ars",
    "alight_code": "하차_정류장표준코드",
    "alight_name": "하차_정류장명",
    "passengers": "승객수",
}
DB_COLUMNS = ["기준일자", "day_type", "노선명", "전환_노선id", "승차_정류장순번", "승차_정류장ars",
              "승차_정류장표준코드", "승차_정류장명", "하차_정류장순번", "하차_정류장ars",
              "하차_정류장표준코드", "하차_정류장명", "승객수", "n_days", "days_seen",
              "window_start", "window_end", "source"]
KEY = ["기준일자", "노선명", "승차_정류장ars", "하차_정류장ars"]
TOLERANCE = 1e-6   # relative, on passenger sums of real numbers


def split_uri(uri: str) -> tuple[str, str]:
    if not uri.startswith("gs://"):
        raise ValueError("Expected gs://bucket/prefix, got " + uri)
    bucket, _, prefix = uri[5:].partition("/")
    return bucket, prefix.strip("/")


class GcsSource:
    def __init__(self, uri: str, client=None):
        from google.cloud import storage
        self.bucket_name, self.prefix = split_uri(uri)
        self.client = client or storage.Client(project=os.environ.get("GCS_PROJECT", "seoul-transit-prod"))

    def names(self) -> list[str]:
        p = f"{self.prefix}/" if self.prefix else ""
        return [b.name[len(p):] for b in self.client.list_blobs(self.bucket_name, prefix=p or None)]

    def read(self, name: str) -> bytes:
        key = f"{self.prefix}/{name}" if self.prefix else name
        return self.client.bucket(self.bucket_name).blob(key).download_as_bytes()


def pick_folder(source, folder: str | None) -> str:
    folders = sorted(m.group(1) for m in map(FOLDER.match, source.names()) if m)
    if folder:
        if folder not in folders:
            raise ValueError(f"No manifest in {folder}/ (complete exports: {folders[-5:]})")
        return folder
    if not folders:
        raise ValueError("No complete export (no manifest.json) found")
    return folders[-1]


def read_export(source, folder: str, day_types: list[str]) -> tuple[pd.DataFrame, dict]:
    manifest = json.loads(source.read(f"{folder}/manifest.json"))
    missing = [d for d in day_types if d not in manifest["day_types"]]
    if missing:
        raise ValueError(f"{folder}: export has no {', '.join(missing)}")
    frames = []
    for part in manifest["parts"]:
        data = source.read(f"{folder}/{part}")
        if part.endswith(".gz"):
            data = gzip.decompress(data)
        frames.append(pd.read_csv(io.BytesIO(data), dtype=str, keep_default_na=False, na_values=[""]))
    raw = pd.concat(frames, ignore_index=True)
    if list(raw.columns) != manifest["columns"]:
        raise ValueError(f"{folder}: columns {list(raw.columns)} differ from the manifest")

    for day_type, want in manifest["day_types"].items():
        part = raw[raw["day_type"] == day_type]
        got = (len(part), float(pd.to_numeric(part["passengers"]).sum()))
        if got[0] != want["rows"] or abs(got[1] - want["passengers"]) > TOLERANCE * max(1.0, want["passengers"]):
            raise ValueError(f"{folder} {day_type}: files hold {got[0]:,} rows / {got[1]:,.3f}, "
                             f"manifest says {want['rows']:,} / {want['passengers']:,.3f}")

    frame = raw[raw["day_type"].isin(day_types)].rename(columns=RENAME)
    for col in ["전환_노선id", "승차_정류장순번", "하차_정류장순번", "n_days", "days_seen"]:
        frame[col] = pd.to_numeric(frame[col]).astype("Int64")
    frame["승객수"] = pd.to_numeric(frame["승객수"])
    frame["source"] = f"od-typical/{folder}"
    dup = frame.duplicated(KEY)
    if dup.any():
        raise ValueError(f"{folder}: {int(dup.sum())} rows repeat a key, e.g. {frame[dup][KEY].iloc[0].tolist()}")
    for col in ["기준일자", "노선명", "승차_정류장ars", "하차_정류장ars"]:
        if frame[col].isna().any():
            raise ValueError(f"{folder}: empty {col}")
    return frame[DB_COLUMNS], manifest


def db_stats(conn, table: str) -> dict[str, tuple[int, float]]:
    rows = conn.execute(text(f"SELECT day_type, COUNT(*) AS n, SUM(승객수) AS p FROM {table} GROUP BY day_type"))
    return {r.day_type: (int(r.n), float(r.p)) for r in rows}


def load(engine, frame: pd.DataFrame, manifest: dict, day_types: list[str]) -> None:
    with engine.begin() as conn:
        conn.exec_driver_sql(SCHEMA_SQL.read_text(encoding="utf-8"))
        nxt = prepare_next_table(conn, TABLE)
        copy_frame(conn, frame, nxt, DB_COLUMNS)
        got = db_stats(conn, nxt)
        problems = []
        for day_type in day_types:
            want = manifest["day_types"][day_type]
            n, p = got.get(day_type, (0, 0.0))
            if n != want["rows"] or abs(p - want["passengers"]) > TOLERANCE * max(1.0, want["passengers"]):
                problems.append(f"{day_type}: {n:,} rows / {p:,.3f} in {nxt}, "
                                f"manifest {want['rows']:,} / {want['passengers']:,.3f}")
        extra = sorted(set(got) - set(day_types))
        if extra:
            problems.append(f"unexpected day types in {nxt}: {extra}")
        if problems:
            raise ValueError("Not published:\n  " + "\n  ".join(problems))
    publish(engine, [TABLE])


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--uri", default=DEFAULT_URI)
    p.add_argument("--folder", help="YYYYMMDD; default the newest complete export")
    p.add_argument("--day-types", default="weekday", help="comma list of " + ", ".join(DAY_TYPES))
    p.add_argument("--db-url", default=os.environ.get("PIPELINE_DB_URL"))
    p.add_argument("--dry-run", action="store_true", help="read and check the export, write nothing")
    args = p.parse_args(argv)

    day_types = [d.strip() for d in args.day_types.split(",") if d.strip()]
    bad = [d for d in day_types if d not in DAY_TYPES]
    if bad or not day_types:
        raise SystemExit(f"--day-types must be some of {', '.join(DAY_TYPES)}, got {args.day_types!r}")

    source = GcsSource(args.uri)
    folder = pick_folder(source, args.folder)
    frame, manifest = read_export(source, folder, day_types)
    summary = ", ".join(f"{d} {int((frame['day_type'] == d).sum()):,} rows / "
                        f"{frame.loc[frame['day_type'] == d, '승객수'].sum():,.1f}" for d in day_types)
    print(f"od-typical/{folder} (window {manifest['window_start']}..{manifest['window_end']}): {summary}", flush=True)
    if args.dry_run:
        return 0
    if not args.db_url:
        raise SystemExit("PIPELINE_DB_URL or --db-url is required")
    load(make_engine(args.db_url), frame, manifest, day_types)
    print(f"LOADED {TABLE}: {len(frame):,} rows from od-typical/{folder}", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
