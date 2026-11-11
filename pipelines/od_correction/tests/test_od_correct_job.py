"""od-correct runs the three correction steps per collected day, on folders here."""

import hashlib
import io
import json
import sys
import zipfile
from pathlib import Path

import pandas as pd
import pytest

SRC = Path(__file__).resolve().parents[1] / "src"
sys.path.insert(0, str(SRC))

import od_correct_job as job  # noqa: E402
import od_structural_correction as sc  # noqa: E402

RAW_HEADER = "기준일자,노선명,승차_정류장ARS,승차_정류장명,하차_정류장ARS,하차_정류장명,승차_정류장순번,하차_정류장순번,승객수"


def raw_csv(yy_mm_dd: str, extra_pax: int = 0) -> bytes:
    rows = [
        f"{yy_mm_dd},100,01001,A,01002,B,1,2,{5 + extra_pax}",
        f"{yy_mm_dd},100,01001,A,01003,C,1,3,4",
        f"{yy_mm_dd},100,01002,B,01002,B,2,2,3",          # same-stop OD
        f"{yy_mm_dd},100,01002,B,01003,C,2,3,6",
        f"{yy_mm_dd},,,뚝섬,,여의도,,,7",                 # ferry
        f"{yy_mm_dd},,,잠실,,,,,2",                        # ferry, no alighting pier
    ]
    return ("\n".join([RAW_HEADER] + rows) + "\n").encode("cp949")


REFERENCE = "\n".join([
    "기준일자|노선명|전환_노선ID|승차_정류장순번|승차_정류장ARS|승차_정류장표준코드|승차_정류장명|하차_정류장순번|하차_정류장ARS|하차_정류장표준코드|하차_정류장명|승객수",
    "25/10/14|100|100100001|1|01001|100000001|A|2|01002|100000002|B|1",
    "25/10/14|100|100100001|2|01002|100000002|B|3|01003|100000003|C|1",
]) + "\n"

MASTER = "정류장번호|노드id\n01001|100000001\n01002|100000002\n01003|100000003\n"


def make_store(tmp_path, months):
    """od-raw folder like transit-od-collect writes: zip + manifest per month."""
    raw = tmp_path / "raw"
    for ym, days in months.items():
        folder = raw / f"{ym[:4]}-{ym[4:]}"
        folder.mkdir(parents=True)
        name = f"대중교통_월간통계자료_{ym[:4]}.{ym[4:]}월.zip"
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            for day in days:
                archive.writestr(f"노선별/노선별OD/노선별OD_{day}.csv", raw_csv(f"{day[2:4]}/{day[4:6]}/{day[6:]}"))
            archive.writestr(f"동별/수단OD/동별_수단OD_{ym}01.csv", b"x")
        (folder / name).write_bytes(buffer.getvalue())
        manifest = {"ym": ym, "file_name": name, "sha256": hashlib.sha256(buffer.getvalue()).hexdigest(),
                    "route_od_days": days}
        (folder / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False), encoding="utf-8")
    (tmp_path / "reference.csv").write_text(REFERENCE, encoding="utf-8-sig")
    (tmp_path / "master.csv").write_text(MASTER, encoding="utf-8")
    return raw


def run(tmp_path, *extra, env=None, monkeypatch=None):
    if monkeypatch and env:
        for k, v in env.items():
            monkeypatch.setenv(k, v)
    return job.main(["--raw-uri", str(tmp_path / "raw"), "--curated-uri", str(tmp_path / "curated"),
                     "--reference", str(tmp_path / "reference.csv"),
                     "--stop-master", str(tmp_path / "master.csv"), *extra])


def test_days_are_corrected_with_report_written_last(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    make_store(tmp_path, {"202609": ["20260915", "20260919"]})
    assert run(tmp_path) == 0
    for day in ("20260915", "20260919"):
        folder = tmp_path / "curated" / day
        report = json.loads((folder / "report.json").read_text(encoding="utf-8"))
        curated = sc.read_od(folder / "curated.csv.gz")
        assert report["structural"]["passenger_diff"] == 0
        assert report["structural"]["passengers"]["input"] == 27
        assert int(curated[sc.PAX].sum()) + report["structural"]["passengers"]["anomaly"] == 27
        assert report["ferry"]["ferry_rows"] == 2
        assert (curated.loc[curated[sc.ROUTE] == "한강버스01", sc.A_NAME].tolist().count("뚝섬")) == 1
        assert report["codes"]["null_boarding_code"] == 2           # the two ferry rows
        assert report["inputs"]["stop_master_rows"] == 3
    assert list((tmp_path / "curated" / "_inputs").glob("stop_master_*.csv"))
    assert "RUN_STATE" in capsys.readouterr().out


def test_second_run_skips_done_days_and_force_redoes_them(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    make_store(tmp_path, {"202609": ["20260915"]})
    run(tmp_path)
    capsys.readouterr()
    run(tmp_path)
    assert "1 already corrected, 0 to do" in capsys.readouterr().out
    run(tmp_path, "--force")
    assert "1 to do" in capsys.readouterr().out


def test_months_before_start_need_only_ym(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    make_store(tmp_path, {"202310": ["20231017"], "202609": ["20260915"]})
    run(tmp_path)
    assert not (tmp_path / "curated" / "20231017").exists()
    run(tmp_path, "--only-ym", "202310")
    assert (tmp_path / "curated" / "20231017" / "report.json").exists()


def test_tasks_split_the_days(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    make_store(tmp_path, {"202609": ["20260915", "20260919", "20260920"]})
    monkeypatch.setenv("CLOUD_RUN_TASK_COUNT", "2")
    monkeypatch.setenv("CLOUD_RUN_TASK_INDEX", "1")
    run(tmp_path)
    assert sorted(p.name for p in (tmp_path / "curated").iterdir() if p.name[0] == "2") == ["20260919"]
    monkeypatch.setenv("CLOUD_RUN_TASK_INDEX", "0")
    run(tmp_path)
    # the second task now sees one day done and splits the remaining two
    assert (tmp_path / "curated" / "20260915" / "report.json").exists()


def test_a_bad_day_fails_alone_and_writes_no_report(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    make_store(tmp_path, {"202609": ["20260915", "20260919"]})
    real = job.sc.correct

    def lose_passengers_on_0915(frame, mode):
        if (frame[sc.DATE] == "260915").any():
            raise sc.ConservationError("passengers not conserved")
        return real(frame, mode)
    monkeypatch.setattr(job.sc, "correct", lose_passengers_on_0915)
    assert run(tmp_path) == 1
    assert sorted(p.parent.name for p in (tmp_path / "curated").glob("*/report.json")) == ["20260919"]
    assert not (tmp_path / "curated" / "20260915" / "curated.csv.gz").exists()
    assert '"failed_days": [{"day": "20260915"' in capsys.readouterr().out


def test_a_zip_that_does_not_match_its_manifest_is_refused(tmp_path, monkeypatch, capsys):
    monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    raw = make_store(tmp_path, {"202609": ["20260915"]})
    manifest = raw / "2026-09" / "manifest.json"
    data = json.loads(manifest.read_text(encoding="utf-8"))
    data["sha256"] = "0" * 64
    manifest.write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    assert run(tmp_path) == 1
    assert not (tmp_path / "curated" / "20260915").exists() or \
        not (tmp_path / "curated" / "20260915" / "report.json").exists()


def test_task_share():
    assert job.task_share(list(range(7)), 0, 3) == [0, 3, 6]
    assert job.task_share(list(range(7)), 2, 3) == [2, 5]
    with pytest.raises(ValueError):
        job.task_share([1], 3, 3)
