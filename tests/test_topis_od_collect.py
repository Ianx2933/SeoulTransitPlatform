"""od-collect copies TOPIS monthly O/D zips into raw storage, unchanged.

No network: a fake session stands in for TOPIS, and LocalStore stands in for
GCS (both stores implement the same five methods).
"""

import io
import json
import zipfile

import pytest
import requests

from pipelines.od_stacking import collect_topis_od as od


# --------------------------------------------------------------------------- #
# helpers
# --------------------------------------------------------------------------- #

def korean_windows_zip(names_and_data):
    """
    A zip whose member names are cp949 bytes without the UTF-8 flag, the way
    Korean Windows tools write them. zipfile always writes non-ASCII names as
    UTF-8, so write ASCII placeholders of the same byte length and patch them.
    """
    buffer = io.BytesIO()
    replacements = []
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        for index, (name, data) in enumerate(names_and_data):
            raw = name.encode("cp949")
            placeholder = (f"P{index:03d}" + "x" * len(raw))[:len(raw)].encode("ascii")
            assert len(placeholder) == len(raw)
            archive.writestr(placeholder.decode("ascii"), data)
            replacements.append((placeholder, raw))
    content = buffer.getvalue()
    for placeholder, raw in replacements:
        assert content.count(placeholder) == 2  # local header + central directory
        content = content.replace(placeholder, raw)
    return content


def month_zip(ym):
    days = [f"{ym}15", f"{ym}19", f"{ym}20"]
    files = [(f"노선별/노선별OD/노선별OD_{d}.csv", "기준일자,노선명\n".encode("cp949")) for d in days]
    files.append((f"동별/수단OD/동별_수단OD_{ym}01.csv", b"x"))
    files.append(("대중교통_월간통계자료.pdf", b"%PDF"))
    return korean_windows_zip(files)


def file_name(ym):
    return f"대중교통_월간통계자료_{ym[:4]}.{ym[4:]}월.zip"


class FakeResponse:
    def __init__(self, status=200, json_body=None, content=b"", headers=None):
        self.status_code = status
        self._json = json_body
        self.content = content
        self.headers = headers or {}
        self.text = content.decode("latin-1") if json_body is None else json.dumps(json_body)

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code}")

    def json(self):
        if self._json is None:
            raise ValueError("not json")
        return self._json

    def iter_content(self, chunk_size):
        for start in range(0, len(self.content), chunk_size):
            yield self.content[start:start + chunk_size]


class FakeTopis:
    """Answers the three TOPIS endpoints from a {YYYYMM: zip bytes} table."""

    def __init__(self, published, path_names=None, download_override=None, fail_first=0):
        self.published = published
        self.path_names = path_names or {}
        self.download_override = download_override
        self.fail_first = fail_first
        self.downloads = []
        self.calls = 0

    def post(self, url, data=None, headers=None, timeout=None, stream=False):
        self.calls += 1
        if self.calls <= self.fail_first:
            raise requests.ConnectionError("reset")
        if url.endswith(od.YEAR_LIST_PATH):
            years = sorted({int(ym[:4]) for ym in self.published} | {2019})
            return FakeResponse(json_body={"rows": [{"bdwrDivCd": str(y)} for y in years]})
        if url.endswith(od.MONTH_LIST_PATH):
            year = data["bdwrDivCd"]
            rows = []
            for month in range(1, 13):
                ym = f"{year}{month:02d}"
                if ym in self.published:
                    rows.append({"monthsTxt": f"{month}월", "bdwrSeq": f"9{ym}",
                                 "apndFileNm": file_name(ym),
                                 "apndFilePathNm": self.path_names.get(ym, f"stored_{ym}")})
                else:
                    rows.append({"monthsTxt": f"{month}월", "bdwrSeq": "", "apndFileNm": "",
                                 "apndFilePathNm": ""})
            return FakeResponse(json_body={"rows": rows})
        if url.endswith(od.DOWNLOAD_PATH):
            ym = data["bdwrSeq"][1:]
            self.downloads.append(ym)
            if self.download_override:
                return self.download_override(ym)
            body = self.published[ym]
            return FakeResponse(content=body, headers={"Content-Type": "application/octet-stream",
                                                       "Content-Length": str(len(body))})
        raise AssertionError(url)


def client(fake):
    return od.TopisClient(session=fake, backoff_seconds=0)


# --------------------------------------------------------------------------- #
# listing
# --------------------------------------------------------------------------- #

def test_unpublished_rows_are_skipped_and_month_comes_from_file_name():
    rows = [
        {"bdwrSeq": "6231", "apndFileNm": "대중교통_월간통계자료_2026.09월.zip", "apndFilePathNm": "a"},
        {"bdwrSeq": "", "apndFileNm": "", "apndFilePathNm": ""},
        {"bdwrSeq": None, "apndFileNm": None, "apndFilePathNm": None},
    ]
    entries = od.parse_month_rows(rows)
    assert [(e.ym, e.bdwr_seq) for e in entries] == [("202609", "6231")]


def test_file_name_without_a_month_fails_instead_of_guessing():
    with pytest.raises(od.TopisError):
        od.parse_month_rows([{"bdwrSeq": "1", "apndFileNm": "자료.zip", "apndFilePathNm": "a"}])


def test_html_instead_of_json_is_an_error():
    class Html:
        def post(self, *a, **k):
            return FakeResponse(content=b"<html>maintenance</html>")
    with pytest.raises(od.TopisError, match="did not return JSON"):
        od.TopisClient(session=Html(), backoff_seconds=0).years()


def test_transient_errors_are_retried():
    fake = FakeTopis({"202609": month_zip("202609")}, fail_first=2)
    assert client(fake).years() == [2019, 2026]


# --------------------------------------------------------------------------- #
# zip checks
# --------------------------------------------------------------------------- #

def test_cp949_member_names_are_decoded(tmp_path):
    path = tmp_path / "a.zip"
    path.write_bytes(month_zip("202609"))
    contents = od.inspect_zip(path, "202609")
    assert contents["route_od_days"] == ["20260915", "20260919", "20260920"]
    assert "노선별/노선별OD/노선별OD_20260915.csv" in [m["name"] for m in contents["members"]]


def test_zip_without_route_od_is_rejected(tmp_path):
    path = tmp_path / "a.zip"
    path.write_bytes(korean_windows_zip([("동별/수단OD/동별_수단OD_20260901.csv", b"x")]))
    with pytest.raises(od.ArchiveError, match="노선별OD"):
        od.inspect_zip(path, "202609")


def test_zip_for_another_month_is_rejected(tmp_path):
    path = tmp_path / "a.zip"
    path.write_bytes(month_zip("202608"))
    with pytest.raises(od.ArchiveError, match="other months"):
        od.inspect_zip(path, "202609")


def test_non_zip_is_rejected(tmp_path):
    path = tmp_path / "a.zip"
    path.write_bytes(b"<html>error</html>")
    with pytest.raises(od.ArchiveError, match="not a zip"):
        od.inspect_zip(path, "202609")


# --------------------------------------------------------------------------- #
# runs
# --------------------------------------------------------------------------- #

def published(*months):
    return {ym: month_zip(ym) for ym in months}


def test_missing_months_are_collected_oldest_first_from_start(tmp_path):
    fake = FakeTopis(published("202312", "202401", "202402", "202501"))
    store = od.LocalStore(tmp_path / "raw")
    state = od.run(client(fake), store, "202401", None, 40, tmp_path)

    assert fake.downloads == ["202401", "202402", "202501"]   # 202312 is before start
    assert state["collected_months"] == ["202401", "202402", "202501"]
    assert state["latest_stored"] == "202501"
    manifest = json.loads((tmp_path / "raw/2024-02/manifest.json").read_text(encoding="utf-8"))
    zip_file = tmp_path / "raw/2024-02" / file_name("202402")
    assert manifest["bytes"] == zip_file.stat().st_size
    assert zip_file.read_bytes() == fake.published["202402"]   # stored unchanged
    assert manifest["route_od_days"] == ["20240215", "20240219", "20240220"]
    assert not list(tmp_path.glob("*.zip"))                    # work files removed


def test_second_run_downloads_nothing(tmp_path):
    fake = FakeTopis(published("202401", "202402"))
    store = od.LocalStore(tmp_path / "raw")
    od.run(client(fake), store, "202401", None, 40, tmp_path)
    fake.downloads.clear()
    state = od.run(client(fake), store, "202401", None, 40, tmp_path)
    assert fake.downloads == []
    assert state["collected_months"] == []
    assert state["latest_stored"] == "202402"


def test_new_month_is_picked_up_on_the_next_run(tmp_path):
    months = published("202401")
    fake = FakeTopis(months)
    store = od.LocalStore(tmp_path / "raw")
    od.run(client(fake), store, "202401", None, 40, tmp_path)
    months["202402"] = month_zip("202402")
    state = od.run(client(fake), store, "202401", None, 40, tmp_path)
    assert state["collected_months"] == ["202402"]


def test_max_months_caps_a_run_and_reports_the_rest(tmp_path):
    fake = FakeTopis(published("202401", "202402", "202403"))
    state = od.run(client(fake), od.LocalStore(tmp_path / "raw"), "202401", None, 2, tmp_path)
    assert state["collected_months"] == ["202401", "202402"]
    assert state["remaining_months"] == ["202403"]


def test_zip_without_manifest_is_collected_again(tmp_path):
    fake = FakeTopis(published("202401"))
    raw = tmp_path / "raw"
    (raw / "2024-01").mkdir(parents=True)
    (raw / "2024-01" / file_name("202401")).write_bytes(b"half uploaded")
    state = od.run(client(fake), od.LocalStore(raw), "202401", None, 40, tmp_path)
    assert state["collected_months"] == ["202401"]
    assert (raw / "2024-01" / file_name("202401")).read_bytes() == fake.published["202401"]


def test_bad_download_stores_no_manifest_and_fails(tmp_path):
    def html(ym):
        return FakeResponse(content=b"<html>", headers={"Content-Type": "text/html;charset=UTF-8"})
    fake = FakeTopis(published("202401"), download_override=html)
    raw = tmp_path / "raw"
    with pytest.raises(od.TopisError, match="instead of a zip"):
        od.run(client(fake), od.LocalStore(raw), "202401", None, 40, tmp_path)
    assert not (raw / "2024-01" / "manifest.json").exists()


def test_truncated_download_fails(tmp_path):
    body = month_zip("202401")

    def short(ym):
        return FakeResponse(content=body[:-10], headers={"Content-Length": str(len(body))})
    fake = FakeTopis(published("202401"), download_override=short)
    with pytest.raises(od.TopisError, match="Content-Length"):
        od.run(client(fake), od.LocalStore(tmp_path / "raw"), "202401", None, 40, tmp_path)


def test_month_changed_at_source_is_reported_not_replaced(tmp_path):
    fake = FakeTopis(published("202401"))
    store = od.LocalStore(tmp_path / "raw")
    od.run(client(fake), store, "202401", None, 40, tmp_path)
    fake.path_names["202401"] = "stored_again"
    fake.downloads.clear()
    state = od.run(client(fake), store, "202401", None, 40, tmp_path)
    assert state["changed_at_source"] == ["202401"]
    assert fake.downloads == []


def test_only_ym_through_main(tmp_path, monkeypatch, capsys):
    fake = FakeTopis(published("202608", "202609"))
    real_client = client(fake)
    monkeypatch.setattr(od, "TopisClient", lambda: real_client)
    monkeypatch.delenv("PIPELINE_STATE_URI", raising=False)
    od.main(["--raw-uri", str(tmp_path / "raw"), "--only-ym", "202609"])
    assert fake.downloads == ["202609"]
    line = next(l for l in capsys.readouterr().out.splitlines() if l.startswith("RUN_STATE "))
    assert json.loads(line[len("RUN_STATE "):])["collected_months"] == ["202609"]


def test_gcs_store_uses_the_prefix():
    class Blob:
        def __init__(self, name):
            self.name = name

    class Bucket:
        def blob(self, name):
            return Blob(name)

    class Client:
        def bucket(self, name):
            assert name == "seoul-transit-prod-pipeline"
            return Bucket()

    store = od.GcsStore("gs://seoul-transit-prod-pipeline/od-raw/", client=Client())
    assert store.describe("2026-09/manifest.json") == \
        "gs://seoul-transit-prod-pipeline/od-raw/2026-09/manifest.json"
