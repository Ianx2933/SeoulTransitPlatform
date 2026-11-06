"""
Build monthly day-type count matrix for weekday decomposition
"""

from __future__ import annotations

import argparse
import os
from sqlalchemy import text

from .common import (
    DEFAULT_HOLIDAY_CSV,
    build_month_counts,
    load_holidays,
    make_engine,
    require_holiday_coverage,
    ym_range,
)


def upsert_month_count(engine, use_ym: str, counts: dict[str, int]) -> None:
    """
    Save one month of day-type counts into month_day_count

    This table is the A matrix source for least-squares decomposition.
    Keeping it materialized makes the estimation reproducible and inspectable.
    """
    sql = text("""
        INSERT INTO month_day_count (
            use_ym, mon, tue, wed, thu, fri, sat, sun_holiday
        )
        VALUES (
            :use_ym, :mon, :tue, :wed, :thu, :fri, :sat, :sun_holiday
        )
        ON CONFLICT (use_ym)
        DO UPDATE SET
            mon = EXCLUDED.mon,
            tue = EXCLUDED.tue,
            wed = EXCLUDED.wed,
            thu = EXCLUDED.thu,
            fri = EXCLUDED.fri,
            sat = EXCLUDED.sat,
            sun_holiday = EXCLUDED.sun_holiday
    """)

    params = {"use_ym": use_ym, **counts}
    with engine.begin() as conn:
        conn.execute(sql, params)


def loaded_month_range(engine) -> tuple[str, str] | None:
    """First and last use_ym in hourly_bus_stop_passenger, or None if it is empty."""
    with engine.connect() as conn:
        row = conn.execute(text(
            "SELECT MIN(use_ym), MAX(use_ym) FROM hourly_bus_stop_passenger"
        )).one()
    if row[0] is None:
        return None
    return str(row[0]), str(row[1])


def main():
    """
    Build month_day_count rows for an inclusive month range.

    Without --start-ym and --end-ym the range is every month the hourly table
    holds, which is what a scheduled run needs after a new month is loaded.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--db-url",
        default=os.environ.get("PIPELINE_DB_URL"),
        required="PIPELINE_DB_URL" not in os.environ,
        help="SQLAlchemy URL. Defaults to PIPELINE_DB_URL, so a scheduler can pass "
             "it from a secret instead of a visible argument.",
    )
    parser.add_argument("--start-ym", help="YYYYMM; default: first month in the hourly table")
    parser.add_argument("--end-ym", help="YYYYMM; default: last month in the hourly table")
    parser.add_argument("--holiday-csv", default=str(DEFAULT_HOLIDAY_CSV))
    args = parser.parse_args()

    if bool(args.start_ym) != bool(args.end_ym):
        parser.error("--start-ym and --end-ym go together; give both or neither")

    holidays = load_holidays(args.holiday_csv)
    engine = make_engine(args.db_url)

    if args.start_ym:
        start_ym, end_ym = args.start_ym, args.end_ym
    else:
        loaded = loaded_month_range(engine)
        if loaded is None:
            raise SystemExit("hourly_bus_stop_passenger is empty; nothing to count.")
        start_ym, end_ym = loaded
        print(f"Month range from hourly_bus_stop_passenger: {start_ym}-{end_ym}")

    months = ym_range(start_ym, end_ym)
    require_holiday_coverage(holidays, {m[:4] for m in months})

    for use_ym in months:
        counts = build_month_counts(use_ym, holidays)
        upsert_month_count(engine, use_ym, counts)
        print(use_ym, counts)


if __name__ == "__main__":
    main()
