"""
Collect TOPIS monthly O/D archives into raw storage.

TOPIS (topis.seoul.go.kr, "대중교통 O/D 현황") publishes one zip per month,
around the 10th of the following month. Each zip holds route-level stop O/D for
three days (weekday, Saturday, Sunday), daily dong-level O/D, and subway O/D.
This job copies every month from --start-ym onward that raw storage does not
hold yet, unchanged, and writes a manifest next to it:

    <raw-uri>/YYYY-MM/<original zip name>
    <raw-uri>/YYYY-MM/manifest.json     sha256, size, member list, TOPIS ids

The manifest is written last, so it doubles as the completion marker: a month
with a zip but no manifest (a run that died during upload) is downloaded again.

Nothing here parses or corrects the CSVs. That is od-correct's job, and keeping
the original bytes means a correction bug can be fixed and re-run without
asking TOPIS again.

    # scheduled form: every missing month from 2024-01, oldest first
    python -m pipelines.od_stacking.collect_topis_od --raw-uri gs://seoul-transit-prod-pipeline/od-raw

    # one month, into a local folder (no GCS needed)
    python -m pipelines.od_stacking.collect_topis_od --raw-uri C:\\tmp\\od-raw --only-ym 202609

The TOPIS endpoints need no login or key. All three are form-encoded POSTs.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import tempfile
import time
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import requests

BASE_URL = "https://topis.seoul.go.kr"
YEAR_LIST_PATH = "/refroom/selectYearList.do"
MONTH_LIST_PATH = "/refroom/selectRefRoomListASC.do"
DOWNLOAD_PATH = "/downloadFileRefRoom.do"
BOARD_CODE = "12"  # blbdDivCd for "대중교통 O/D 현황"

DEFAULT_START_YM = "202401"
STATE_URI_ENV = "PIPELINE_STATE_URI"
MANIFEST_NAME = "manifest.json"

# Route-level stop O/D is the part od-correct needs. A zip without it is
# treated as broken rather than stored as if it were complete.
ROUTE_OD_PATTERN = re.compile(r"노선별OD_(\d{8})\.csv$")
FILE_MONTH_PATTERN = re.compile(r"(\d{4})\.(\d{1,2})월")

HEADERS = {
    "User-Agent": "seoul-transit-platform-od-collect/1.0",
    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
}


class TopisError(RuntimeError):
    """TOPIS answered with something other than what the job expects."""


class ArchiveError(RuntimeError):
    """A downloaded file is not a usable O/D zip."""


# --------------------------------------------------------------------------- #
# TOPIS
# --------------------------------------------------------------------------- #

@dataclass(frozen=True)
class MonthEntry:
    ym: str               # YYYYMM
    bdwr_seq: str
    file_name: str        # apndFileNm, e.g. 대중교통_월간통계자료_2026.09월.zip
    file_path_name: str   # apndFilePathNm, TOPIS's stored name


def month_from_file_name(file_name: str) -> str | None:
    """'대중교통_월간통계자료_2026.09월.zip' -> '202609'."""
    match = FILE_MONTH_PATTERN.search(file_name or "")
    if not match:
        return None
    year, month = int(match.group(1)), int(match.group(2))
    if not 1 <= month <= 12:
        return None
    return f"{year:04d}{month:02d}"


def parse_month_rows(rows: list[dict]) -> list[MonthEntry]:
    """
    Turn selectRefRoomListASC rows into entries, skipping unpublished months.

    The list always has twelve rows; a month that is not out yet has empty
    file fields. A row that has a file but whose month cannot be read fails
    the run: guessing would store data under the wrong month.
    """
    entries = []
    for row in rows or []:
        file_name = (row.get("apndFileNm") or "").strip()
        file_path_name = (row.get("apndFilePathNm") or "").strip()
        bdwr_seq = str(row.get("bdwrSeq") or "").strip()
        if not file_name or not file_path_name or not bdwr_seq:
            continue
        ym = month_from_file_name(file_name)
        if ym is None:
            raise TopisError(f"Cannot read the month from TOPIS file name {file_name!r}")
        entries.append(MonthEntry(ym, bdwr_seq, file_name, file_path_name))
    return entries


class TopisClient:
    def __init__(self, session: requests.Session | None = None, base_url: str = BASE_URL,
                 retries: int = 3, backoff_seconds: float = 5.0, timeout: float = 60.0):
        self.session = session or requests.Session()
        self.base_url = base_url
        self.retries = retries
        self.backoff_seconds = backoff_seconds
        self.timeout = timeout

    def _post(self, path: str, data: dict, stream: bool = False) -> requests.Response:
        last_error: Exception | None = None
        for attempt in range(1, self.retries + 1):
            try:
                response = self.session.post(
                    self.base_url + path, data=data, headers=HEADERS,
                    timeout=self.timeout, stream=stream,
                )
                response.raise_for_status()
                return response
            except requests.RequestException as error:
                last_error = error
                if attempt < self.retries:
                    print(f"  TOPIS {path} attempt {attempt} failed ({type(error).__name__}); retrying")
                    time.sleep(self.backoff_seconds * attempt)
        raise TopisError(f"TOPIS {path} failed after {self.retries} attempts: {last_error}")

    def _post_json(self, path: str, data: dict) -> dict:
        response = self._post(path, data)
        try:
            body = response.json()
        except ValueError:
            snippet = response.text[:200].replace("\n", " ")
            raise TopisError(f"TOPIS {path} did not return JSON: {snippet!r}") from None
        if not isinstance(body, dict) or "rows" not in body:
            raise TopisError(f"TOPIS {path} returned JSON without 'rows'")
        return body

    def years(self) -> list[int]:
        body = self._post_json(YEAR_LIST_PATH, {"blbdDivCd": BOARD_CODE})
        years = []
        for row in body["rows"]:
            value = str(row.get("bdwrDivCd") or "").strip()
            if value.isdigit():
                years.append(int(value))
        if not years:
            raise TopisError("TOPIS year list is empty")
        return sorted(set(years))

    def months(self, year: int) -> list[MonthEntry]:
        body = self._post_json(MONTH_LIST_PATH, {
            "blbdDivCd": BOARD_CODE, "bdwrDivCd": str(year), "mainBdwrRowNum": "12",
        })
        return parse_month_rows(body["rows"])

    def download(self, entry: MonthEntry, destination: Path) -> tuple[int, str]:
        """Stream one zip to ``destination``; return (bytes, sha256)."""
        response = self._post(DOWNLOAD_PATH, {
            "apndFileNm": entry.file_name,
            "apndFilePathNm": entry.file_path_name,
            "bdwrSeq": entry.bdwr_seq,
            "blbdDivCd": BOARD_CODE,
        }, stream=True)
        content_type = response.headers.get("Content-Type", "")
        if "html" in content_type.lower() or "json" in content_type.lower():
            raise TopisError(f"TOPIS returned {content_type!r} instead of a zip for {entry.ym}")
        digest = hashlib.sha256()
        size = 0
        with open(destination, "wb") as handle:
            for chunk in response.iter_content(chunk_size=1024 * 1024):
                if not chunk:
                    continue
                handle.write(chunk)
                digest.update(chunk)
                size += len(chunk)
        expected = response.headers.get("Content-Length")
        if expected and expected.isdigit() and int(expected) != size:
            raise TopisError(f"{entry.ym}: received {size:,} bytes, Content-Length said {int(expected):,}")
        return size, digest.hexdigest()


# --------------------------------------------------------------------------- #
# Zip inspection
# --------------------------------------------------------------------------- #

def member_name(info: zipfile.ZipInfo) -> str:
    """
    Decode a member name the way Korean Windows zip tools wrote it.

    Without the UTF-8 flag (bit 11) the name bytes are cp949, but zipfile
    decodes them as cp437, which turns 노선별OD into mojibake and breaks every
    pattern below.
    """
    if info.flag_bits & 0x800:
        return info.filename
    try:
        return info.filename.encode("cp437").decode("cp949")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return info.filename


def inspect_zip(path: Path, ym: str) -> dict:
    """
    Check the zip opens, every member's CRC matches, and route O/D files for
    ``ym`` are inside. Return the member list for the manifest.
    """
    try:
        archive = zipfile.ZipFile(path)
    except zipfile.BadZipFile as error:
        raise ArchiveError(f"{ym}: not a zip file ({error})") from None
    with archive:
        bad = archive.testzip()
        if bad is not None:
            raise ArchiveError(f"{ym}: CRC mismatch in {member_name(archive.getinfo(bad))}")
        members = []
        route_od_days = []
        for info in archive.infolist():
            if info.is_dir():
                continue
            name = member_name(info)
            members.append({"name": name, "size": info.file_size, "crc32": f"{info.CRC:08x}"})
            match = ROUTE_OD_PATTERN.search(name)
            if match:
                route_od_days.append(match.group(1))
    wrong_month = [day for day in route_od_days if day[:6] != ym]
    if not route_od_days:
        raise ArchiveError(f"{ym}: no 노선별OD_YYYYMMDD.csv inside the zip")
    if wrong_month:
        raise ArchiveError(f"{ym}: route O/D files for other months: {wrong_month}")
    return {"members": members, "route_od_days": sorted(route_od_days)}


# --------------------------------------------------------------------------- #
# Raw storage: GCS in the cloud, a folder for local runs and tests
# --------------------------------------------------------------------------- #

class LocalStore:
    def __init__(self, root: str):
        self.root = Path(root)

    def exists(self, key: str) -> bool:
        return (self.root / key).is_file()

    def read_text(self, key: str) -> str:
        return (self.root / key).read_text(encoding="utf-8")

    def upload_file(self, key: str, source: Path, content_type: str) -> None:
        target = self.root / key
        target.parent.mkdir(parents=True, exist_ok=True)
        tmp = target.with_name(target.name + ".part")
        shutil.copyfile(source, tmp)
        tmp.replace(target)

    def write_text(self, key: str, text: str, content_type: str) -> None:
        target = self.root / key
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def describe(self, key: str) -> str:
        return str(self.root / key)


class GcsStore:
    def __init__(self, uri: str, client=None):
        bucket, _, prefix = uri[5:].partition("/")
        if not bucket:
            raise ValueError(f"Expected gs://bucket[/prefix], got: {uri}")
        if client is None:
            from google.cloud import storage  # only needed for gs:// targets
            client = storage.Client()
        self.bucket_name = bucket
        self.bucket = client.bucket(bucket)
        self.prefix = prefix.strip("/")

    def _name(self, key: str) -> str:
        return f"{self.prefix}/{key}" if self.prefix else key

    def exists(self, key: str) -> bool:
        return self.bucket.blob(self._name(key)).exists()

    def read_text(self, key: str) -> str:
        return self.bucket.blob(self._name(key)).download_as_text(encoding="utf-8")

    def upload_file(self, key: str, source: Path, content_type: str) -> None:
        blob = self.bucket.blob(self._name(key))
        blob.upload_from_filename(str(source), content_type=content_type, timeout=600)

    def write_text(self, key: str, text: str, content_type: str) -> None:
        self.bucket.blob(self._name(key)).upload_from_string(text, content_type=content_type)

    def describe(self, key: str) -> str:
        return f"gs://{self.bucket_name}/{self._name(key)}"


def open_store(uri: str):
    return GcsStore(uri) if uri.startswith("gs://") else LocalStore(uri)


def month_folder(ym: str) -> str:
    return f"{ym[:4]}-{ym[4:]}"


# --------------------------------------------------------------------------- #
# Run
# --------------------------------------------------------------------------- #

def check_ym(value: str) -> str:
    if len(value) != 6 or not value.isdigit() or not 1 <= int(value[4:]) <= 12:
        raise argparse.ArgumentTypeError(f"Expected YYYYMM, got: {value}")
    return value


def available_months(client: TopisClient, start_ym: str, end_ym: str | None) -> list[MonthEntry]:
    entries: dict[str, MonthEntry] = {}
    for year in client.years():
        if year < int(start_ym[:4]) or (end_ym and year > int(end_ym[:4])):
            continue
        for entry in client.months(year):
            if entry.ym < start_ym or (end_ym and entry.ym > end_ym):
                continue
            if entry.ym in entries:
                raise TopisError(f"TOPIS lists {entry.ym} twice "
                                 f"({entries[entry.ym].file_name}, {entry.file_name})")
            entries[entry.ym] = entry
    return [entries[ym] for ym in sorted(entries)]


def stored_manifest(store, ym: str) -> dict | None:
    key = f"{month_folder(ym)}/{MANIFEST_NAME}"
    if not store.exists(key):
        return None
    return json.loads(store.read_text(key))


def collect_month(client: TopisClient, store, entry: MonthEntry, work_dir: Path) -> dict:
    """Download, check, upload the zip, then the manifest. Return the manifest."""
    zip_path = work_dir / f"{entry.ym}.zip"
    try:
        started = time.monotonic()
        size, sha256 = client.download(entry, zip_path)
        contents = inspect_zip(zip_path, entry.ym)
        zip_key = f"{month_folder(entry.ym)}/{entry.file_name}"
        store.upload_file(zip_key, zip_path, "application/zip")
        manifest = {
            "ym": entry.ym,
            "source": "TOPIS 대중교통 O/D 현황",
            "bdwr_seq": entry.bdwr_seq,
            "file_name": entry.file_name,
            "file_path_name": entry.file_path_name,
            "object": store.describe(zip_key),
            "bytes": size,
            "sha256": sha256,
            "collected_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            **contents,
        }
        store.write_text(f"{month_folder(entry.ym)}/{MANIFEST_NAME}",
                         json.dumps(manifest, ensure_ascii=False, indent=2),
                         "application/json; charset=utf-8")
        print(f"  {entry.ym}: {size:,} bytes, sha256 {sha256[:12]}..., "
              f"{len(contents['members'])} files, route O/D days {contents['route_od_days']} "
              f"({time.monotonic() - started:.0f}s)")
        return manifest
    finally:
        zip_path.unlink(missing_ok=True)


def write_run_state(uri: str, state: dict) -> None:
    """Same contract as the hourly collector: one new object per workflow run."""
    if not uri.startswith("gs://") or "/" not in uri[5:]:
        raise ValueError(f"{STATE_URI_ENV} must look like gs://bucket/object, got: {uri}")
    bucket_name, object_name = uri[5:].split("/", 1)
    from google.cloud import storage

    blob = storage.Client().bucket(bucket_name).blob(object_name)
    blob.upload_from_string(json.dumps(state, ensure_ascii=False), content_type="application/json")
    print(f"Run state written to {uri}")


def run(client: TopisClient, store, start_ym: str, end_ym: str | None,
        max_months: int, work_dir: Path) -> dict:
    available = available_months(client, start_ym, end_ym)
    print(f"TOPIS months from {start_ym}: {len(available)} "
          f"({available[0].ym if available else '-'} .. {available[-1].ym if available else '-'})")

    already, missing, changed = [], [], []
    for entry in available:
        manifest = stored_manifest(store, entry.ym)
        if manifest is None:
            missing.append(entry)
            continue
        already.append(entry.ym)
        # TOPIS re-uploading a month gives it a new stored name. Not replaced
        # automatically: the stored copy may already be loaded downstream.
        if manifest.get("file_path_name") != entry.file_path_name:
            changed.append(entry.ym)

    todo = missing[:max_months]
    print(f"Stored already: {len(already)}; missing: {len(missing)}; this run: "
          f"{[e.ym for e in todo] or 'none'}")
    if changed:
        print(f"WARNING: TOPIS file changed since collection for {changed}. "
              f"Stored copies were kept; delete a month's manifest to fetch it again.")

    collected = []
    for position, entry in enumerate(todo, start=1):
        print(f"[{position}/{len(todo)}] {entry.ym} ({entry.file_name})")
        collect_month(client, store, entry, work_dir)
        collected.append(entry.ym)

    stored = sorted(set(already) | set(collected))
    return {
        "finished_at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "source": "topis-od",
        "latest_available": available[-1].ym if available else None,
        "collected_months": collected,
        "remaining_months": [e.ym for e in missing[len(todo):]],
        "changed_at_source": changed,
        "latest_stored": stored[-1] if stored else None,
    }


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Copy TOPIS monthly O/D zips into raw storage.")
    parser.add_argument("--raw-uri", default=os.getenv("OD_RAW_URI"),
                        help="gs://bucket/prefix or a local folder. Defaults to OD_RAW_URI.")
    parser.add_argument("--start-ym", type=check_ym, default=DEFAULT_START_YM)
    parser.add_argument("--end-ym", type=check_ym, default=None)
    parser.add_argument("--only-ym", type=check_ym, default=None,
                        help="Collect just this month (same as --start-ym X --end-ym X).")
    parser.add_argument("--max-months", type=int, default=40,
                        help="Upper bound on months downloaded in one run.")
    args = parser.parse_args(argv)

    if not args.raw_uri:
        raise SystemExit("Raw storage is required. Pass --raw-uri or set OD_RAW_URI.")
    if args.only_ym:
        args.start_ym = args.end_ym = args.only_ym
    if args.end_ym and args.end_ym < args.start_ym:
        raise SystemExit(f"--end-ym {args.end_ym} is before --start-ym {args.start_ym}")

    store = open_store(args.raw_uri)
    with tempfile.TemporaryDirectory(prefix="od-collect-") as work:
        state = run(TopisClient(), store, args.start_ym, args.end_ym,
                    args.max_months, Path(work))

    print("RUN_STATE " + json.dumps(state, ensure_ascii=False))
    uri = os.environ.get(STATE_URI_ENV)
    if uri:
        write_run_state(uri, state)


if __name__ == "__main__":
    main()
