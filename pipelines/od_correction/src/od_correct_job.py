"""
Correct every collected TOPIS route OD day and store the result.

For each day listed in an od-raw manifest (route_od_days), this job

  1. takes 노선별OD_YYYYMMDD.csv out of the stored zip,
  2. fills standard codes (od_correction_pipeline.py) against the stop master,
  3. gives Han River ferry rows a route and pier positions (ferry_od.py),
  4. runs the structural correction in fixed mode (od_structural_correction.py),
     which fails the day unless passengers in = passengers out + anomaly,
  5. writes curated.csv.gz, anomaly.csv.gz and report.json to
     <curated-uri>/YYYYMMDD/. report.json is written last and marks the day done;
     a day with a report is skipped unless --force.

The stop master is read from Cloud SQL with the query in STOP_MASTER.md (Seoul
stops plus the stops outside Seoul that only integrated_bus_stop_location holds),
unless --stop-master gives a file. Its SHA-256 goes into every report, and a copy
is kept under <curated-uri>/_inputs/.

A failed day is reported and the others carry on; the run exits non-zero if any
day failed. On Cloud Run Jobs with several tasks, each task takes every N-th day
(CLOUD_RUN_TASK_INDEX, CLOUD_RUN_TASK_COUNT).

    # cloud: everything collected from 2024-01 that has no result yet
    python pipelines/od_correction/src/od_correct_job.py

    # local test on folders, no GCS or database
    python pipelines/od_correction/src/od_correct_job.py --raw-uri C:/tmp/od-raw ^
        --curated-uri C:/tmp/od-curated --reference data/reference/od_reference_251014.csv ^
        --stop-master C:/Users/miyum/stop_master.csv --only-ym 202609
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
import time
import traceback
import zipfile
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

SRC = Path(__file__).resolve().parent
REPO = SRC.parents[2]
if str(REPO) not in sys.path:
    sys.path.insert(0, str(REPO))

import ferry_od  # noqa: E402
import od_correction_pipeline as ocp  # noqa: E402
import od_structural_correction as sc  # noqa: E402
from pipelines.od_stacking.collect_topis_od import (  # noqa: E402
    ROUTE_OD_PATTERN, GcsStore, LocalStore, member_name, open_store,
)

STOP_MASTER_SQL = """
SELECT 정류장번호, 노드id FROM bus_stop_location
UNION
SELECT ars_id, canonical_node_id FROM integrated_bus_stop_location
WHERE ars_id IS NOT NULL AND ars_id NOT IN (SELECT 정류장번호 FROM bus_stop_location)
"""
DEFAULT_START_YM = "202401"
REPORT = "report.json"
STATE_URI_ENV = "PIPELINE_STATE_URI"


# --------------------------------------------------------------------------- #
# Storage helpers (stores come from collect_topis_od)
# --------------------------------------------------------------------------- #

def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def download(store, key: str, destination: Path) -> None:
    if isinstance(store, LocalStore):
        shutil.copyfile(store.root / key, destination)
    else:
        store.bucket.blob(store._name(key)).download_to_filename(str(destination), timeout=600)


def fetch_uri(uri: str, destination: Path) -> None:
    """A gs://bucket/object or a local path, copied to destination."""
    if uri.startswith("gs://"):
        bucket, _, key = uri[5:].partition("/")
        download(GcsStore(f"gs://{bucket}"), key, destination)
    else:
        shutil.copyfile(uri, destination)


def manifest_months(store) -> list[str]:
    """YYYY-MM folders that hold a manifest.json."""
    if isinstance(store, LocalStore):
        return sorted(p.parent.name for p in store.root.glob("*/manifest.json"))
    prefix = f"{store.prefix}/" if store.prefix else ""
    months = set()
    for blob in store.bucket.client.list_blobs(store.bucket_name, prefix=prefix):
        rest = blob.name[len(prefix):]
        if rest.count("/") == 1 and rest.endswith("/manifest.json"):
            months.add(rest.split("/")[0])
    return sorted(months)


# --------------------------------------------------------------------------- #
# Which days
# --------------------------------------------------------------------------- #

def planned_days(raw, start_ym: str, end_ym: str | None, only_ym: str | None,
                 only_day: str | None) -> list[dict]:
    days = []
    for folder in manifest_months(raw):
        ym = folder.replace("-", "")
        if only_ym and ym != only_ym:
            continue
        if not only_ym and not only_day and (ym < start_ym or (end_ym and ym > end_ym)):
            continue
        manifest = json.loads(raw.read_text(f"{folder}/manifest.json"))
        for day in manifest["route_od_days"]:
            if only_day and day != only_day:
                continue
            days.append({"day": day, "folder": folder, "zip": manifest["file_name"],
                         "zip_sha256": manifest["sha256"]})
    return sorted(days, key=lambda d: d["day"])


def task_share(days: list, index: int, count: int) -> list:
    if not 0 <= index < count:
        raise ValueError(f"task index {index} outside 0..{count - 1}")
    return days[index::count]


# --------------------------------------------------------------------------- #
# Inputs read once per task
# --------------------------------------------------------------------------- #

def stop_master_from_db(db_url: str, destination: Path) -> int:
    from sqlalchemy import create_engine  # only needed when reading Cloud SQL

    engine = create_engine(db_url)
    try:
        with engine.connect() as conn:
            frame = pd.read_sql_query(STOP_MASTER_SQL, conn)
    finally:
        engine.dispose()
    frame.columns = ["정류장번호", "노드id"]
    frame.to_csv(destination, sep="|", index=False, encoding="utf-8")
    return len(frame)


def extract_day(zip_path: Path, day: str, destination: Path) -> str:
    with zipfile.ZipFile(zip_path) as archive:
        for info in archive.infolist():
            name = member_name(info)
            match = ROUTE_OD_PATTERN.search(name)
            if match and match.group(1) == day:
                with archive.open(info) as source, open(destination, "wb") as target:
                    shutil.copyfileobj(source, target)
                return name
    raise FileNotFoundError(f"노선별OD_{day}.csv not in {zip_path.name}")


# --------------------------------------------------------------------------- #
# One day
# --------------------------------------------------------------------------- #

def correct_day(item: dict, raw, curated, work: Path, reference: Path, master: Path,
                inputs: dict, zip_cache: dict) -> dict:
    started = time.monotonic()
    day, folder = item["day"], item["folder"]
    zip_path = zip_cache.get(folder)
    if zip_path is None:
        zip_path = work / f"{folder}.zip"
        download(raw, f"{folder}/{item['zip']}", zip_path)
        if sha256_of(zip_path) != item["zip_sha256"]:
            raise ValueError(f"{folder}/{item['zip']} does not match its manifest sha256")
        zip_cache[folder] = zip_path

    day_dir = work / day
    day_dir.mkdir(exist_ok=True)
    raw_csv = day_dir / "raw.csv"
    member = extract_day(zip_path, day, raw_csv)

    codes_csv = day_dir / "codes.csv"
    pipeline = ocp.ODCorrectionPipeline(ocp.PipelineConfig(
        raw_path=raw_csv, reference_path=reference, output_path=codes_csv, stop_master_path=master))
    pipeline.run()
    codes = pipeline.curated_df

    frame = sc.read_od(codes_csv)
    frame, ferry_report = ferry_od.normalize_ferry(frame)
    out, anomaly, structural = sc.correct(frame, "fixed")    # raises ConservationError

    sc.write_od(out, day_dir / "curated.csv.gz")
    sc.write_od(anomaly, day_dir / "anomaly.csv.gz")
    curated.upload_file(f"{day}/curated.csv.gz", day_dir / "curated.csv.gz", "application/gzip")
    curated.upload_file(f"{day}/anomaly.csv.gz", day_dir / "anomaly.csv.gz", "application/gzip")

    report = {
        "day": day,
        "finished_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": {"zip": raw.describe(f"{folder}/{item['zip']}"), "zip_sha256": item["zip_sha256"],
                   "member": member, "raw_sha256": sha256_of(raw_csv)},
        "inputs": inputs,
        "image": os.environ.get("OD_CORRECT_IMAGE"),
        "codes": {
            "rows": len(codes),
            "null_route_id": int(codes["전환_노선ID"].eq("").sum()),
            "null_boarding_code": int(codes["승차_정류장표준코드"].eq("").sum()),
            "null_alighting_code": int(codes["하차_정류장표준코드"].eq("").sum()),
        },
        "ferry": ferry_report,
        "structural": structural,
        "outputs": {"curated": curated.describe(f"{day}/curated.csv.gz"),
                    "anomaly": curated.describe(f"{day}/anomaly.csv.gz")},
        "seconds": round(time.monotonic() - started, 1),
    }
    curated.write_text(f"{day}/{REPORT}", json.dumps(report, ensure_ascii=False, indent=2),
                       "application/json; charset=utf-8")
    shutil.rmtree(day_dir, ignore_errors=True)
    return report


# --------------------------------------------------------------------------- #
# Run
# --------------------------------------------------------------------------- #

def write_run_state(uri: str, state: dict) -> None:
    bucket, _, key = uri[5:].partition("/")
    GcsStore(f"gs://{bucket}").bucket.blob(key).upload_from_string(
        json.dumps(state, ensure_ascii=False), content_type="application/json")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Correct collected TOPIS route OD days.")
    parser.add_argument("--raw-uri", default=os.getenv("OD_RAW_URI"))
    parser.add_argument("--curated-uri", default=os.getenv("OD_CURATED_URI"))
    parser.add_argument("--reference", default=os.getenv("OD_REFERENCE_URI"),
                        help="gs:// object or local path of the reference CSV")
    parser.add_argument("--stop-master", default=None, help="Local stop master file instead of Cloud SQL")
    parser.add_argument("--db-url", default=os.getenv("PIPELINE_DB_URL"))
    parser.add_argument("--start-ym", default=DEFAULT_START_YM)
    parser.add_argument("--end-ym", default=None)
    parser.add_argument("--only-ym", default=None)
    parser.add_argument("--only-day", default=None, help="YYYYMMDD")
    parser.add_argument("--force", action="store_true", help="Redo days that already have a report")
    args = parser.parse_args(argv)

    for name in ("raw_uri", "curated_uri", "reference"):
        if not getattr(args, name):
            raise SystemExit(f"--{name.replace('_', '-')} is required (or its OD_* variable)")
    if not args.stop_master and not args.db_url:
        raise SystemExit("Give --stop-master or PIPELINE_DB_URL for the stop master.")

    index = int(os.getenv("CLOUD_RUN_TASK_INDEX", "0"))
    count = int(os.getenv("CLOUD_RUN_TASK_COUNT", "1"))
    raw, curated = open_store(args.raw_uri), open_store(args.curated_uri)

    days = planned_days(raw, args.start_ym, args.end_ym, args.only_ym, args.only_day)
    done = [d["day"] for d in days if curated.exists(f"{d['day']}/{REPORT}")]
    todo = days if args.force else [d for d in days if d["day"] not in done]
    mine = task_share(todo, index, count)
    print(f"task {index + 1}/{count}: {len(days)} days collected, {len(done)} already corrected, "
          f"{len(todo)} to do, this task {[d['day'] for d in mine] or 'none'}", flush=True)

    corrected, failed = [], []
    if mine:
        with tempfile.TemporaryDirectory(prefix="od-correct-") as tmp:
            work = Path(tmp)
            reference = work / "reference.csv"
            fetch_uri(args.reference, reference)
            master = work / "stop_master.csv"
            if args.stop_master:
                shutil.copyfile(args.stop_master, master)
                master_rows = sum(1 for _ in open(master, encoding="utf-8-sig")) - 1
                master_source = "file"
            else:
                master_rows = stop_master_from_db(args.db_url, master)
                master_source = "cloud sql"
            inputs = {
                "reference": args.reference, "reference_sha256": sha256_of(reference),
                "stop_master": master_source, "stop_master_rows": master_rows,
                "stop_master_sha256": sha256_of(master),
            }
            key = f"_inputs/stop_master_{inputs['stop_master_sha256'][:16]}.csv"
            if not curated.exists(key):
                curated.upload_file(key, master, "text/csv")
            print(f"inputs: {json.dumps(inputs, ensure_ascii=False)}", flush=True)

            zip_cache: dict = {}
            for position, item in enumerate(mine, start=1):
                print(f"[{position}/{len(mine)}] {item['day']}", flush=True)
                try:
                    report = correct_day(item, raw, curated, work, reference, master, inputs, zip_cache)
                    p = report["structural"]["passengers"]
                    print(f"  {item['day']}: {p['input']:,} passengers -> {p['output']:,} + {p['anomaly']:,} "
                          f"anomaly, ferry {report['ferry']['ferry_passengers']:,}, {report['seconds']}s",
                          flush=True)
                    corrected.append(item["day"])
                except Exception as error:   # one bad day must not stop the rest
                    traceback.print_exc()
                    failed.append({"day": item["day"], "error": f"{type(error).__name__}: {error}"[:500]})

    state = {"finished_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
             "task": index, "tasks": count, "corrected_days": corrected, "failed_days": failed,
             "already_done": len(done)}
    print("RUN_STATE " + json.dumps(state, ensure_ascii=False), flush=True)
    uri = os.getenv(STATE_URI_ENV)
    if uri:
        write_run_state(uri if count == 1 else f"{uri}.task{index}", state)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
