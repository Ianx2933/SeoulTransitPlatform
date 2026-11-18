import gzip
import json
from pathlib import Path

import pytest

from pipelines.od_dong import load_od_dong as m

ROOT = Path(__file__).resolve().parents[1]
HEADER = "day_type,origin_cd,origin_name,dest_cd,dest_name,subway_rides,bus_rides,total_rides\n"


class FakeSource:
    def __init__(self, files):
        self.files = files

    def names(self):
        return list(self.files)

    def read(self, name):
        return self.files[name]


def export(rows, manifest_days=None, columns=None):
    body = HEADER + "".join(rows)
    days = {}
    for line in rows:
        d, *_rest = line.strip().split(",")
        total = float(line.strip().split(",")[-1])
        days.setdefault(d, {"rows": 0, "total_rides": 0.0})
        days[d]["rows"] += 1
        days[d]["total_rides"] += total
    manifest = {"day_types": manifest_days or days, "parts": ["part-0.csv.gz"],
                "columns": columns or HEADER.strip().split(",")}
    return {"20261118/manifest.json": json.dumps(manifest).encode(),
            "20261118/part-0.csv.gz": gzip.compress(body.encode())}


ROWS = [
    "weekday,11010530,사직동,outside,outside,10,5,15\n",
    "weekday,11010530,사직동,11010540,삼청동,1,,1\n",
    "saturday,11010530,사직동,outside,outside,8,2,10\n",
    "sunday_holiday,11010530,사직동,outside,outside,6,1,7\n",
]


def test_schema_copy_matches_the_database_schema():
    copy = ROOT / "pipelines" / "od_dong" / "od_dong_typical.sql"
    original = ROOT / "database" / "schema" / "07_od_dong_typical.sql"
    assert copy.read_text(encoding="utf-8") == original.read_text(encoding="utf-8")


def test_reads_and_checks_an_export():
    frame, manifest = m.read_export(FakeSource(export(ROWS)), "20261118")
    assert list(frame.columns) == m.DB_COLUMNS
    assert len(frame) == 4
    assert frame.loc[1, "bus_rides"] == 0.0           # empty number becomes 0
    assert manifest["day_types"]["weekday"]["rows"] == 2


def test_rejects_a_missing_day_type():
    with pytest.raises(ValueError, match="no sunday_holiday"):
        m.read_export(FakeSource(export(ROWS[:3])), "20261118")


def test_rejects_numbers_that_differ_from_the_manifest():
    days = {"weekday": {"rows": 2, "total_rides": 99.0}, "saturday": {"rows": 1, "total_rides": 10.0},
            "sunday_holiday": {"rows": 1, "total_rides": 7.0}}
    with pytest.raises(ValueError, match="manifest says"):
        m.read_export(FakeSource(export(ROWS, manifest_days=days)), "20261118")


def test_rejects_a_repeated_key():
    with pytest.raises(ValueError, match="repeat a key"):
        m.read_export(FakeSource(export(ROWS + [ROWS[0]])), "20261118")


def test_problems_against_lists_what_differs():
    manifest = {"day_types": {"weekday": {"rows": 2, "total_rides": 16.0}}}
    assert m.problems_against(manifest, {"weekday": (2, 16.0)}) == []
    assert m.problems_against(manifest, {"weekday": (1, 16.0)})
    assert m.problems_against(manifest, {"weekday": (2, 16.0), "holiday": (1, 1.0)})
