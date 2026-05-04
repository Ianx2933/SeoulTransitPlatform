"""
Load Seoul Open API monthly hourly bus stop passenger data into PostgreSQL.

The CardBusTimeNew API returns wide-format monthly rows:
one route-stop row contains HR_0 ... HR_23 columns.
This script unpivots those columns into one row per hour.
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


def main():
    """
    CLI entry point.
    """
    load_dotenv()

    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--api-key", default=os.getenv("SEOUL_API_KEY"))
    parser.add_argument("--use-ym", required=True, help="YYYYMM, e.g. 202501")
    parser.add_argument("--route-no", default=None)
    parser.add_argument("--page-size", type=int, default=1000)
    parser.add_argument("--sleep", type=float, default=0.1)
    args = parser.parse_args()

    if not args.api_key:
        raise ValueError("API key is required. Pass --api-key or set SEOUL_API_KEY.")

    engine = make_engine(args.db_url)

    start = 1
    total = None
    inserted_rows = 0

    while total is None or start <= total:
        end = start + args.page_size - 1
        total, records = fetch_page(args.api_key, start, end, args.use_ym, args.route_no)
        upsert_records(engine, records)

        inserted_rows += len(records)
        print(f"Fetched API rows {start}-{end}; unpivoted rows={len(records)}; total API rows={total}")

        start = end + 1
        time.sleep(args.sleep)

    print(f"Done. Inserted/updated unpivoted hourly rows: {inserted_rows}")


if __name__ == "__main__":
    main()
