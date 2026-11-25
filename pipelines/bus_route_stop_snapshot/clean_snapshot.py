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

Also reads the monthly .xlsx files of the Seoul open data portal (OA-1095,
"서울시버스노선별정류소정보(20261102).xlsx"): columns ROUTE_ID, 노선명, 순번,
NODE_ID, ARS_ID, 정류소명, X좌표, Y좌표, with the snapshot date only in the
file name. Read with the standard library (no openpyxl needed).

Usage (from the repository root):
    python -m pipelines.bus_route_stop_snapshot.clean_snapshot <file.csv|file.xlsx> [...]
writes bus_route_stop_snapshot_clean.csv in the current folder.
"""

from __future__ import annotations

import csv
import re
import sys
import zipfile
import xml.etree.ElementTree as ET
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


XLSX_COLUMNS = ["ROUTE_ID", "노선명", "순번", "NODE_ID", "ARS_ID", "정류소명", "X좌표", "Y좌표"]
_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}


def _col_index(ref: str) -> int:
    letters = re.match(r"[A-Z]+", ref).group(0)
    n = 0
    for ch in letters:
        n = n * 26 + (ord(ch) - 64)
    return n - 1


def read_xlsx(path: Path) -> list[list[str]]:
    """Rows of the first sheet as text (shared strings resolved), header first."""
    with zipfile.ZipFile(path) as z:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in z.namelist():
            root = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for si in root.findall("m:si", _NS):
                shared.append("".join(t.text or "" for t in si.iter("{%s}t" % _NS["m"])))
        sheets = sorted(n for n in z.namelist() if re.fullmatch(r"xl/worksheets/sheet\d+\.xml", n))
        if not sheets:
            raise ValueError(f"{path}: no worksheet")
        root = ET.fromstring(z.read(sheets[0]))
    rows: list[list[str]] = []
    for row in root.iter("{%s}row" % _NS["m"]):
        cells: dict[int, str] = {}
        for c in row.findall("m:c", _NS):
            kind = c.get("t")
            if kind == "inlineStr":
                value = "".join(t.text or "" for t in c.iter("{%s}t" % _NS["m"]))
            else:
                v = c.find("m:v", _NS)
                value = "" if v is None or v.text is None else v.text
                if kind == "s":
                    value = shared[int(value)]
            cells[_col_index(c.get("r"))] = value
        if cells:
            rows.append([cells.get(i, "") for i in range(max(cells) + 1)])
    return rows


def xlsx_date(path: Path) -> date:
    m = re.search(r"(20\d{2})(\d{2})(\d{2})", path.name)
    if not m:
        raise ValueError(f"{path}: no snapshot date (YYYYMMDD) in the file name")
    return date(int(m.group(1)), int(m.group(2)), int(m.group(3)))


def _number_text(value: str) -> str:
    """Excel keeps whole numbers as text like 100100165 or 1.00100165E8; back to digits."""
    s = str(value).strip()
    if re.fullmatch(r"-?\d+\.0+", s) or re.fullmatch(r"-?\d+(\.\d+)?[eE][+-]?\d+", s):
        return str(int(float(s)))
    return s


def xlsx_records(path: Path) -> list[dict]:
    rows = read_xlsx(path)
    if not rows or [h.strip() for h in rows[0][:8]] != XLSX_COLUMNS:
        raise ValueError(f"{path}: unexpected header {rows[0][:8] if rows else ''}")
    day = xlsx_date(path).isoformat()
    out = []
    for n, r in enumerate(rows[1:], start=2):
        r = (r + [""] * 8)[:8]
        if not any(x.strip() for x in r):
            continue
        route_id, route_nm, seq, node_id, ars, name, x, y = r
        lng, lat = float(x), float(y)
        if not (124 < lng < 132 and 33 < lat < 39):
            raise ValueError(f"{path.name}:{n}: position outside Korea")
        out.append({"stdr_de": day, "route_id": _number_text(route_id), "route_nm": route_nm.strip(),
                    "seq": int(float(seq)), "node_id": _number_text(node_id) or None,
                    "ars": pad_ars(_number_text(ars)), "node_nm": name.strip(), "lng": lng, "lat": lat})
    return out


def clean(paths: list[Path], out: Path, log=print) -> dict:
    seen: set[tuple] = set()
    counts: dict[str, int] = {}
    skipped = 0
    with out.open("w", encoding="utf-8", newline="") as fh:
        w = csv.DictWriter(fh, fieldnames=OUT_COLUMNS)
        w.writeheader()
        for path in paths:
            if path.suffix.lower() == ".xlsx":
                for row in xlsx_records(path):
                    key = (row["stdr_de"], row["route_id"], row["seq"])
                    if key in seen:
                        continue
                    seen.add(key)
                    counts[row["stdr_de"]] = counts.get(row["stdr_de"], 0) + 1
                    w.writerow(row)
                continue
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
