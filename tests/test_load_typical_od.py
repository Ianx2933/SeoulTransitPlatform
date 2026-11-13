"""Offline checks for pipelines/od_typical/load_typical_od.py (no database, no GCS)."""
import gzip
import io
import json

import pandas as pd
import pytest

from pipelines.od_typical import load_typical_od as app

COLUMNS = ["day_type", "label", "mode", "route_name", "route_id", "board_seq", "board_key", "board_code",
           "board_name", "alight_seq", "alight_key", "alight_code", "alight_name", "passengers",
           "n_days", "days_seen", "window_start", "window_end"]


def rows():
    return [
        ["weekday", "typical_weekday", "bus", "100", "100100130", "3", "01041", "101000043", "A", "5", "02006",
         "", "B", "12.5", "10", "9", "2025-10-14", "2026-09-15"],
        ["weekday", "typical_weekday", "ferry", "한강버스01", "", "1", "name:마곡", "", "마곡", "2", "name:망원",
         "", "망원", "0.25", "10", "2", "2025-10-14", "2026-09-15"],
        ["saturday", "typical_saturday", "bus", "100", "100100130", "3", "01041", "101000043", "A", "5", "02006",
         "", "B", "7.0", "12", "12", "2025-10-14", "2026-09-15"],
    ]


class FakeSource:
    def __init__(self, data_rows=None, manifest_patch=None, folders=("20260816", "20260915")):
        data_rows = rows() if data_rows is None else data_rows
        frame = pd.DataFrame(data_rows, columns=COLUMNS)
        buf = io.StringIO()
        frame.to_csv(buf, index=False)
        manifest = {
            "window_start": "20251014", "window_end": "20260915", "columns": COLUMNS,
            "parts": ["part-000000000000.csv.gz"],
            "day_types": {d: {"rows": int((frame.day_type == d).sum()),
                              "passengers": float(pd.to_numeric(frame[frame.day_type == d].passengers).sum())}
                          for d in frame.day_type.unique()},
        }
        manifest.update(manifest_patch or {})
        self.files = {}
        for f in folders:
            self.files[f"{f}/part-000000000000.csv.gz"] = gzip.compress(buf.getvalue().encode())
            self.files[f"{f}/manifest.json"] = json.dumps(manifest).encode()
        self.files["20261001/part-000000000000.csv.gz"] = b"interrupted, no manifest"

    def names(self):
        return list(self.files)

    def read(self, name):
        return self.files[name]


def test_newest_complete_folder_is_picked():
    assert app.pick_folder(FakeSource(), None) == "20260915"
    assert app.pick_folder(FakeSource(), "20260816") == "20260816"
    with pytest.raises(ValueError):
        app.pick_folder(FakeSource(), "20261001")


def test_only_requested_day_types_are_kept_and_renamed():
    frame, _ = app.read_export(FakeSource(), "20260915", ["weekday"])
    assert list(frame.columns) == app.DB_COLUMNS
    assert set(frame.day_type) == {"weekday"}
    assert frame["승객수"].sum() == pytest.approx(12.75)
    ferry = frame[frame["노선명"] == "한강버스01"].iloc[0]
    assert ferry["승차_정류장ars"] == "name:마곡"
    assert pd.isna(ferry["전환_노선id"])
    assert set(frame["source"]) == {"od-typical/20260915"}


def test_files_that_disagree_with_the_manifest_stop_the_load():
    bad = FakeSource(manifest_patch={"day_types": {"weekday": {"rows": 2, "passengers": 99.0},
                                                   "saturday": {"rows": 1, "passengers": 7.0}}})
    with pytest.raises(ValueError, match="manifest says"):
        app.read_export(bad, "20260915", ["weekday"])


def test_a_missing_day_type_stops_the_load():
    only_weekday = [r for r in rows() if r[0] == "weekday"]
    with pytest.raises(ValueError, match="no saturday"):
        app.read_export(FakeSource(only_weekday), "20260915", ["weekday", "saturday"])


def test_a_repeated_key_stops_the_load():
    r = rows()
    with pytest.raises(ValueError, match="repeat a key"):
        app.read_export(FakeSource(r[:1] + r[:1]), "20260915", ["weekday"])


def test_unknown_day_type_argument_is_refused():
    with pytest.raises(SystemExit):
        app.main(["--day-types", "holiday", "--dry-run"])


def test_the_packaged_schema_matches_database_schema():
    # The job image has pipelines/ only, so it runs the packaged copy.
    from pathlib import Path
    repo = Path(app.__file__).resolve().parents[2]
    assert app.SCHEMA_SQL.read_text(encoding="utf-8") == \
        (repo / "database" / "schema" / "09_od_typical_route_stop.sql").read_text(encoding="utf-8")
