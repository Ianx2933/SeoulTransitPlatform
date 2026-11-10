"""
Run the Java odcorrection SQL on a copy of one curated OD file, in temp tables.

This is the reference for the pandas port: the same input goes through
sql/java_correction_steps.sql here and through od_structural_correction.py
(mode=faithful) there, and compare_corrections.py checks the two outputs are
the same rows.

Safety: everything happens in one transaction that is rolled back, on temp
tables, with search_path set to pg_temp only. analysis_table_final and
anomaly_data are never named, so a typo cannot reach them. Use the local
database (5432), not Cloud SQL.

    python pipelines/od_correction/src/run_java_baseline.py ^
        --input  data/od/curated_codes_20260915.csv ^
        --output data/od/java_baseline_20260915.csv ^
        --anomaly data/od/java_baseline_anomaly_20260915.csv ^
        --psql "C:\\Program Files\\PostgreSQL\\16\\bin\\psql.exe" ^
        --dsn "host=localhost port=5432 dbname=Seoul_Transit user=postgres"

The input is the pipe-separated, utf-8-sig output of od_correction_pipeline.py.
An empty field is NULL, as when that file is loaded with COPY.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

COLUMNS = [
    "기준일자", "노선명", "전환_노선ID", "승차_정류장순번", "승차_정류장ARS", "승차_정류장표준코드",
    "승차_정류장명", "하차_정류장순번", "하차_정류장ARS", "하차_정류장표준코드", "하차_정류장명", "승객수",
]

TABLE_DDL = """
CREATE TEMP TABLE od_work (
    기준일자 text, 노선명 text, 전환_노선id text, 승차_정류장순번 integer, 승차_정류장ars text,
    승차_정류장표준코드 text, 승차_정류장명 text, 하차_정류장순번 integer, 하차_정류장ars text,
    하차_정류장표준코드 text, 하차_정류장명 text, 승객수 bigint
) ON COMMIT DROP;
CREATE TEMP TABLE od_anomaly (LIKE od_work) ON COMMIT DROP;
"""

ORDER_BY = ("기준일자, 노선명 COLLATE \"C\", 승차_정류장순번, 승차_정류장ars COLLATE \"C\", "
            "하차_정류장순번, 하차_정류장ars COLLATE \"C\", 승객수")

SQL_DIR = Path(__file__).resolve().parents[1] / "sql"
STEPS_FILE = SQL_DIR / "java_correction_steps.sql"
DATE_PATTERN = re.compile(r"^\d{6}(\d{2})?$")
TAG_PATTERN = re.compile(r"^(UPDATE|DELETE|INSERT \d+|SELECT) (\d+)$")


def psql_path(path: Path) -> str:
    """A path quoted for a psql meta-command: forward slashes, quotes doubled."""
    return "'" + path.resolve().as_posix().replace("'", "''") + "'"


def read_header_and_dates(path: Path) -> list[str]:
    with open(path, encoding="utf-8-sig", newline="") as handle:
        header = handle.readline().rstrip("\r\n").split("|")
        if header != COLUMNS:
            raise SystemExit(f"Unexpected columns in {path}:\n  {header}\nexpected:\n  {COLUMNS}")
        dates = set()
        for line in handle:
            dates.add(line.split("|", 1)[0])
    dates.discard("")
    bad = [d for d in dates if not DATE_PATTERN.match(d)]
    if bad:
        raise SystemExit(f"기준일자 values that are not 6 or 8 digits: {sorted(bad)[:5]}")
    return sorted(dates)


def build_script(input_path: Path, output_path: Path, anomaly_path: Path, dates: list[str]) -> str:
    copy_opts = "WITH (FORMAT csv, HEADER true, DELIMITER '|', ENCODING 'UTF8')"
    lines = [
        "\\set ON_ERROR_STOP on",
        "\\encoding UTF8",
        "BEGIN;",
        "SET LOCAL search_path = pg_temp;",
        "SET LOCAL client_min_messages = warning;",
        TABLE_DDL,
        f"\\copy od_work FROM {psql_path(input_path)} {copy_opts}",
    ]
    for date in dates:
        lines += [f"\\set basedate '{date}'", f"\\echo DATE {date}", f"\\i {psql_path(STEPS_FILE)}"]
    lines += [
        f"\\copy (SELECT * FROM od_work ORDER BY {ORDER_BY}) TO {psql_path(output_path)} {copy_opts}",
        f"\\copy (SELECT * FROM od_anomaly ORDER BY {ORDER_BY}) TO {psql_path(anomaly_path)} {copy_opts}",
        "ROLLBACK;",
    ]
    return "\n".join(lines) + "\n"


def parse_step_counts(output: str) -> list[dict]:
    """Pair each '\\echo STEP name' with the command tags that follow it."""
    steps: list[dict] = []
    date = None
    for raw in output.splitlines():
        line = raw.strip()
        if line.startswith("DATE "):
            date = line[5:]
        elif line.startswith("STEP "):
            steps.append({"date": date, "step": line[5:], "tags": []})
        elif steps:
            match = TAG_PATTERN.match(line)
            if match:
                steps[-1]["tags"].append([match.group(1).split()[0], int(match.group(2))])
    return steps


def run(psql: str, dsn: str | None, input_path: Path, output_path: Path, anomaly_path: Path,
        report_path: Path | None = None) -> list[dict]:
    dates = read_header_and_dates(input_path)
    if not dates:
        raise SystemExit(f"No rows in {input_path}")
    output_path.parent.mkdir(parents=True, exist_ok=True)
    anomaly_path.parent.mkdir(parents=True, exist_ok=True)
    script = build_script(input_path, output_path, anomaly_path, dates)

    env = dict(os.environ, PGCLIENTENCODING="UTF8")
    with tempfile.TemporaryDirectory(prefix="java-baseline-") as work:
        script_path = Path(work) / "run.sql"
        script_path.write_text(script, encoding="utf-8")
        command = [psql, "-X", "-v", "ON_ERROR_STOP=1", "-f", str(script_path)]
        if dsn:
            command.insert(2, dsn)
        result = subprocess.run(command, capture_output=True, env=env)
    out = result.stdout.decode("utf-8", errors="replace")
    err = result.stderr.decode("utf-8", errors="replace")
    if result.returncode != 0:
        sys.stderr.write(err)
        raise SystemExit(f"psql failed with exit code {result.returncode}")
    if err.strip():
        sys.stderr.write(err)

    steps = parse_step_counts(out)
    for step in steps:
        tags = ", ".join(f"{kind} {count:,}" for kind, count in step["tags"]) or "-"
        print(f"  {step['date'] or '':>8} {step['step']:<30} {tags}")
    if report_path:
        report_path.write_text(json.dumps(steps, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"Baseline output: {output_path}")
    print(f"Baseline anomaly: {anomaly_path}")
    return steps


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Run the Java OD correction SQL on temp tables.")
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--anomaly", required=True, type=Path)
    parser.add_argument("--report", type=Path, default=None, help="Step counts as JSON")
    parser.add_argument("--psql", default="psql")
    parser.add_argument("--dsn", default=None,
                        help="libpq connection string. Defaults to PGHOST/PGPORT/... variables.")
    args = parser.parse_args(argv)
    run(args.psql, args.dsn, args.input, args.output, args.anomaly, args.report)


if __name__ == "__main__":
    main()
