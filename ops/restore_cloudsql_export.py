"""
Restore a Cloud SQL SQL export (.sql.gz in Cloud Storage) into a local database.

Streams gcloud storage cat -> gunzip -> psql, so neither the compressed nor the
uncompressed dump is written to disk: only the restored database needs space.
The bytes never pass through a PowerShell pipeline, which would re-encode them.

The target database must exist and be empty. Statements that fail are logged
and counted rather than stopping the restore: a Cloud SQL dump grants to roles
that exist only in Cloud SQL (cloudsqlsuperuser and friends), and those GRANT
and OWNER lines are expected to fail locally. Look at the log for anything else.

    python ops/restore_cloudsql_export.py ^
        --uri gs://seoul-transit-prod-pg-backups/cloudsql/seoul_transit_20261110.sql.gz ^
        --psql "C:\\Program Files\\PostgreSQL\\18\\bin\\psql.exe" ^
        --dsn "host=localhost port=5432 dbname=Seoul_Transit_restore user=postgres" ^
        --log restore_20261110.log
"""

from __future__ import annotations

import argparse
import gzip
import os
import re
import shutil
import subprocess
import sys
import threading
import time
from collections import Counter
from pathlib import Path

CHUNK = 4 * 1024 * 1024
EXPECTED_ROLE_ERRORS = re.compile(r'role "(cloudsql[a-z_]*|cloudsqlsuperuser|cloudsqladmin)" does not exist')


def gcloud_command() -> str:
    found = shutil.which("gcloud") or shutil.which("gcloud.cmd")
    if not found:
        raise SystemExit("gcloud not found on PATH")
    return found


def pump(source, sink, counter: list) -> None:
    try:
        while True:
            block = source.read(CHUNK)
            if not block:
                break
            sink.write(block)
            counter[0] += len(block)
    except (BrokenPipeError, OSError) as error:   # psql exited early; its exit code says why
        print(f"  stopped sending: {type(error).__name__}", flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Stream a Cloud SQL .sql.gz export into a local database.")
    parser.add_argument("--uri", required=True)
    parser.add_argument("--psql", required=True)
    parser.add_argument("--dsn", required=True, help="libpq string for the (empty) target database")
    parser.add_argument("--log", required=True, type=Path, help="psql messages go here")
    args = parser.parse_args(argv)

    target = re.search(r"dbname=(\S+)", args.dsn)
    if not target or target.group(1).strip("'\"") == "Seoul_Transit":
        raise SystemExit("Give dbname= of a new, empty database, not Seoul_Transit itself.")

    env = dict(os.environ, PGCLIENTENCODING="UTF8")
    env.pop("PGPASSWORD", None)  # local connections use pgpass.conf
    source = subprocess.Popen([gcloud_command(), "storage", "cat", args.uri], stdout=subprocess.PIPE)
    with open(args.log, "wb") as log:
        psql = subprocess.Popen([args.psql, "-X", "-q", "-v", "ON_ERROR_STOP=0", args.dsn, "-f", "-"],
                                stdin=subprocess.PIPE, stdout=log, stderr=log, env=env)
        read = [0]
        started = time.monotonic()
        worker = threading.Thread(target=pump, args=(gzip.GzipFile(fileobj=source.stdout), psql.stdin, read))
        worker.start()
        while worker.is_alive():
            worker.join(60)
            elapsed = time.monotonic() - started
            print(f"  {read[0] / 1e9:6.2f} GB of SQL sent, {elapsed / 60:5.1f} min", flush=True)
        try:
            psql.stdin.close()
        except (BrokenPipeError, OSError):
            pass
        psql_code = psql.wait()
    source_code = source.wait()

    text = args.log.read_text(encoding="utf-8", errors="replace")
    errors = [line for line in text.splitlines() if "ERROR:" in line]
    expected = [line for line in errors if EXPECTED_ROLE_ERRORS.search(line)]
    other = [line for line in errors if not EXPECTED_ROLE_ERRORS.search(line)]
    kinds = Counter(re.sub(r'"[^"]*"', '"..."', line.split("ERROR:", 1)[1].strip()) for line in other)

    print(f"\ngcloud exit {source_code}, psql exit {psql_code}, {read[0] / 1e9:.2f} GB of SQL, "
          f"{(time.monotonic() - started) / 60:.1f} min")
    print(f"errors: {len(errors)} ({len(expected)} about Cloud SQL-only roles, {len(other)} other)")
    for kind, count in kinds.most_common(15):
        print(f"  {count:>5} x {kind}")
    return 0 if source_code == 0 and psql_code == 0 and not other else 1


if __name__ == "__main__":
    sys.exit(main())
