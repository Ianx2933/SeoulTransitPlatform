"""
Cleans files of the Seoul route master (route, stop order, stop number and
position by snapshot date) into one UTF-8 CSV that load_snapshot.sql copies
into bus_route_stop_snapshot.

Input columns: STDR_DE,ROUTE_ID,ROUTE_NM,STTN_SN,NODE_ID,STTN_NO,NODE_NM,CRDNT_X,CRDNT_Y
(cp949 or UTF-8, unquoted). Two things the files do that a plain CSV reader
gets wrong:
- a stop name may contain a comma ("공항중학교.마곡엠밸리9,10단지"); those
  lines also lack the trailing comma, so the name is everything between the
  sixth field and the two coordinates;
- stop numbers lose their leading zero ("3689" is 03689); "0" means no number.

Usage (from the repository root):
    python -m pipelines.bus_route_stop_snapshot.clean_snapshot <file.csv> [<file.csv> ...]
writes bus_route_stop_snapshot_clean.csv in the current folder.
"""

from __future__ import annotations

import csv
import sys
from datetime import date
from pathlib import Path

OUT_NAME = "bus_route_stop_snapshot_clean.csv"
OUT_COLUMNS = ["stdr_de", "route_id", "route_nm", "seq", "node_id", "ars", "node_nm", "lng", "lat"]
HEADER_START = "STDR_DE,ROUTE_ID,ROUTE_NM,STTN_SN,NODE_ID,STTN_NO,NODE_NM"


def read_text(path: Path) -> str:
    raw = path.read_bytes()
    for enc in ("utf-8-sig", "cp949"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            continue
    raise ValueError(f"{path}: neither UTF-8 nor cp949")


def pad_ars(value: str) -> str | None:
    s = value.strip()
    if not s or s == "0":
        return None
    return s.zfill(5) if s.isdigit() and len(s) < 5 else s


def parse_line(line: str) -> dict | None:
    """One data line as a row, or None for a blank line. Raises ValueError on a bad line."""
    line = line.rstrip("\r\n")
    if not line.strip():
        return None
    f = line.split(",")
    if f and f[-1] == "":
        f = f[:-1]
    if len(f) < 9:
        raise ValueError(f"too few fields: {line[:80]}")
    stdr, route_id, route_nm, seq, node_id, sttn_no = f[:6]
    lng, lat = float(f[-2]), float(f[-1])
    name = ",".join(f[6:-2]).strip()
    if not (124 < lng < 132 and 33 < lat < 39):
        raise ValueError(f"position outside Korea: {line[:80]}")
    day = date(int(stdr[:4]), int(stdr[4:6]), int(stdr[6:8]))
    return {"stdr_de": day.isoformat(), "route_id": route_id.strip(), "route_nm": route_nm.strip(),
            "seq": int(seq), "node_id": node_id.strip() or None, "ars": pad_ars(sttn_no),
            "node_nm": name, "lng": lng, "lat": lat}


def clean(paths: list[Path], out: Path, log=print) -> dict:
    seen: set[tuple] = set()
    counts: dict[str, int] = {}
    skipped = 0
    with out.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=OUT_COLUMNS)
        w.writeheader()
        for path in paths:
            lines = read_text(path).splitlines()
            if not lines or not lines[0].lstrip("﻿").startswith(HEADER_START):
                raise ValueError(f"{path}: unexpected header {lines[0][:80] if lines else ''}")
            for n, line in enumerate(lines[1:], start=2):
                try:
                    row = parse_line(line)
                except ValueError as e:
                    skipped += 1
                    log(f"  skipped {path.name}:{n}: {e}")
                    continue
                if row is None:
                    continue
                key = (row["stdr_de"], row["route_id"], row["seq"])
                if key in seen:
                    continue
                seen.add(key)
                counts[row["stdr_de"]] = counts.get(row["stdr_de"], 0) + 1
                w.writerow(row)
    for d in sorted(counts):
        log(f"{d}: {counts[d]:,} rows")
    log(f"skipped {skipped} lines; wrote {out}")
    return counts


def main(argv: list[str]) -> int:
    if not argv:
        print(__doc__)
        return 2
    clean([Path(a) for a in argv], Path(OUT_NAME))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
