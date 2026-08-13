"""
Load Seoul Open API monthly hourly bus stop passenger data into PostgreSQL.

The CardBusTimeNew API returns wide-format monthly rows:
one route-stop row contains HR_0 ... HR_23 columns.
This script unpivots those columns into one row per hour.

Supports both a single month and a backfill over a month range:

    # one month
    python load_hourly_boarding.py --use-ym 202501

    # backfill
    python load_hourly_boarding.py --start-ym 202502 --end-ym 202512

Daily totals are derived from this table rather than stored separately.
The predecessor project kept a separate daily_od_data table fed by the
CardBusStatisticsServiceNew API; that table is no longer used, because
PredictionFeatureService now reads lag features from the corrected OD table,
and a daily figure can be summed from hourly rows on demand:

    SELECT use_ym, route_no, stop_ars,
           SUM(boarding_passengers)  AS boarding,
           SUM(alighting_passengers) AS alighting
    FROM hourly_bus_stop_passenger
    GROUP BY use_ym, route_no, stop_ars;

"""

from __future__ import annotations

import argparse
import os
import time
import xml.etree.ElementTree as ET

import requests
from sqlalchemy import text
from dotenv import load_dotenv

from common import make_engine, normalize_ars


API_NAME = "CardBusTimeNew"
BASE_URL = "http://openapi.seoul.go.kr:8088"


def get_text(row: ET.Element, tag: str, default: str = "") -> str:
    """
    Safely read text from an XML tag.
    """
    node = row.find(tag)
    return node.text.strip() if node is not None and node.text is not None else default


def get_number(row: ET.Element, *tags: str) -> float:
    """
    Read a numeric value from one of several possible XML tag names.

    The API sample contains inconsistent tag names such as HR_1_GET_ON_NOPE
    instead of HR_1_GET_ON_TNOPE. Supporting aliases prevents fragile parsing.
    """
    for tag in tags:
        raw = get_text(row, tag, "")
        if raw != "":
            try:
                return float(raw)
            except ValueError:
                return 0.0
    return 0.0


def build_url(api_key: str, start: int, end: int, use_ym: str, route_no: str | None = None) -> str:
    """
    Build the Seoul Open API request URL.
    """
    if route_no:
        return f"{BASE_URL}/{api_key}/xml/{API_NAME}/{start}/{end}/{use_ym}/{route_no}/"
    return f"{BASE_URL}/{api_key}/xml/{API_NAME}/{start}/{end}/{use_ym}/"


def fetch_page(api_key: str, start: int, end: int, use_ym: str, route_no: str | None = None) -> tuple[int, list[dict]]:
    """
    Fetch one API page and convert rows into normalized hourly records.

    Returned records are already unpivoted, so DB insertion can stay simple.
    This keeps API parsing separate from database persistence.
    """
    url = build_url(api_key, start, end, use_ym, route_no)
    response = requests.get(url, timeout=30)
    response.raise_for_status()

    root = ET.fromstring(response.content)

    result_node = root.find("RESULT")
    if result_node is not None:
        result_code = get_text(result_node, "CODE", "")
        if result_code and result_code != "INFO-000":
            message = get_text(result_node, "MESSAGE", "")
            raise RuntimeError(f"Seoul API error: {result_code} {message}")

    total_raw = get_text(root, "list_total_count", "0")
    total_count = int(total_raw) if total_raw.isdigit() else 0

    records = []
    for row in root.findall("row"):
        use_ym_value = get_text(row, "USE_YM")
        route_no_value = get_text(row, "RTE_NO")
        route_name = get_text(row, "RTE_NM")
        stop_id = get_text(row, "STOPS_ID")
        stop_ars = normalize_ars(get_text(row, "STOPS_ARS_NO"))
        stop_name = get_text(row, "SBWY_STNS_NM")
        reg_ymd = get_text(row, "REG_YMD")

        for hour in range(24):
            boarding = get_number(row, f"HR_{hour}_GET_ON_TNOPE", f"HR_{hour}_GET_ON_NOPE")
            alighting = get_number(row, f"HR_{hour}_GET_OFF_TNOPE", f"HR_{hour}_GET_OFF_NOPE")

            records.append({
                "use_ym": use_ym_value,
                "route_no": route_no_value,
                "route_name": route_name,
                "stop_id": stop_id,
                "stop_ars": stop_ars,
                "stop_name": stop_name,
                "hour": hour,
                "boarding_passengers": boarding,
                "alighting_passengers": alighting,
                "reg_ymd": reg_ymd,
            })

    return total_count, records


def upsert_records(engine, records: list[dict]) -> None:
    """
    Insert or update hourly passenger records.

    ON CONFLICT makes the loader idempotent. Re-running the same month updates records
    instead of duplicating them.
    """
    if not records:
        return

    sql = text("""
        INSERT INTO hourly_bus_stop_passenger (
            use_ym, route_no, route_name, stop_id, stop_ars, stop_name,
            hour, boarding_passengers, alighting_passengers, reg_ymd
        )
        VALUES (
            :use_ym, :route_no, :route_name, :stop_id, :stop_ars, :stop_name,
            :hour, :boarding_passengers, :alighting_passengers, :reg_ymd
        )
        ON CONFLICT (use_ym, route_no, stop_ars, hour)
        DO UPDATE SET
            route_name = EXCLUDED.route_name,
            stop_id = EXCLUDED.stop_id,
            stop_name = EXCLUDED.stop_name,
            boarding_passengers = EXCLUDED.boarding_passengers,
            alighting_passengers = EXCLUDED.alighting_passengers,
            reg_ymd = EXCLUDED.reg_ymd
    """)

    with engine.begin() as conn:
        conn.execute(sql, records)


def month_range(start_ym: str, end_ym: str) -> list[str]:
    """
    List YYYYMM strings from start_ym to end_ym inclusive.

    Implemented with plain arithmetic rather than a date library because the
    API takes a month string, and converting through dates would invite
    day-of-month and timezone questions that do not apply here.

    """
    for value in (start_ym, end_ym):
        if len(value) != 6 or not value.isdigit():
            raise ValueError(f"Expected YYYYMM, got: {value}")
        if not 1 <= int(value[4:]) <= 12:
            raise ValueError(f"Month must be 01-12, got: {value}")

    start_index = int(start_ym[:4]) * 12 + int(start_ym[4:]) - 1
    end_index = int(end_ym[:4]) * 12 + int(end_ym[4:]) - 1

    if start_index > end_index:
        raise ValueError(f"start_ym {start_ym} is after end_ym {end_ym}")

    months = []
    for index in range(start_index, end_index + 1):
        months.append(f"{index // 12:04d}{index % 12 + 1:02d}")
    return months


def load_month(engine, api_key: str, use_ym: str, route_no: str | None,
               page_size: int, sleep_seconds: float) -> int:
    """
    Load one month, paging until the API's reported total is reached.
    """
    start = 1
    total = None
    inserted_rows = 0

    while total is None or start <= total:
        end = start + page_size - 1
        total, records = fetch_page(api_key, start, end, use_ym, route_no)
        upsert_records(engine, records)

        inserted_rows += len(records)
        print(f"  {use_ym}: API rows {start}-{end}; unpivoted={len(records)}; total={total}")

        start = end + 1
        time.sleep(sleep_seconds)

    return inserted_rows


def main():
    """
    CLI entry point.
    """
    load_dotenv()

    parser = argparse.ArgumentParser(
        description="Load hourly bus stop passenger data for one month or a range."
    )
    parser.add_argument(
        "--db-url",
        default=os.getenv("PIPELINE_DB_URL"),
        help="SQLAlchemy URL. Defaults to PIPELINE_DB_URL.",
    )
    parser.add_argument("--api-key", default=os.getenv("SEOUL_API_KEY"))
    parser.add_argument("--use-ym", help="Single month, YYYYMM, e.g. 202501")
    parser.add_argument("--start-ym", help="Backfill start month, YYYYMM")
    parser.add_argument("--end-ym", help="Backfill end month, YYYYMM")
    parser.add_argument("--route-no", default=None)
    parser.add_argument("--page-size", type=int, default=1000)
    parser.add_argument("--sleep", type=float, default=0.1)
    parser.add_argument(
        "--continue-on-error",
        action="store_true",
        help="Keep going if one month fails, and report failures at the end ",
    )
    args = parser.parse_args()

    if not args.db_url:
        raise SystemExit(
            "Database URL is required. Pass --db-url or set PIPELINE_DB_URL.\n"
            "Example:\n"
            "  export PIPELINE_DB_URL="
            "'postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'"
        )

    if not args.api_key:
        raise SystemExit("API key is required. Pass --api-key or set SEOUL_API_KEY.")

    # Exactly one of the two modes must be given, so a typo in --start-ym does not silently fall back to loading a single month.
    single = args.use_ym is not None
    ranged = args.start_ym is not None or args.end_ym is not None

    if single and ranged:
        raise SystemExit("Use either --use-ym or --start-ym/--end-ym, not both.")
    if not single and not ranged:
        raise SystemExit("Specify --use-ym, or --start-ym and --end-ym.")
    if ranged and not (args.start_ym and args.end_ym):
        raise SystemExit("Backfill needs both --start-ym and --end-ym.")

    months = [args.use_ym] if single else month_range(args.start_ym, args.end_ym)

    engine = make_engine(args.db_url)

    total_rows = 0
    failures: list[tuple[str, str]] = []

    for position, use_ym in enumerate(months, start=1):
        print(f"[{position}/{len(months)}] {use_ym}")
        try:
            total_rows += load_month(
                engine, args.api_key, use_ym, args.route_no,
                args.page_size, args.sleep,
            )
        except Exception as error:
            if not args.continue_on_error:
                raise
            # A backfill can span months the API has no data for. Recording the
            # failure and moving on is better than losing the months already  loaded.
            print(f"  {use_ym}: FAILED - {error}")
            failures.append((use_ym, str(error)))

    print(f"\nDone. Months processed: {len(months) - len(failures)}/{len(months)}")
    print(f"Inserted/updated unpivoted hourly rows: {total_rows}")

    if failures:
        print(f"\nFailed months ({len(failures)}):")
        for use_ym, message in failures:
            print(f"  {use_ym}: {message}")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
