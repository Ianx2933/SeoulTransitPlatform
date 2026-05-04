"""
Build monthly day-type count matrix for weekday decomposition
"""

from __future__ import annotations

import argparse
from sqlalchemy import text

from common import build_month_counts, load_holidays, make_engine, ym_range


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


def main():
    """
    Build month_day_count rows for an inclusive month range.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--start-ym", required=True)
    parser.add_argument("--end-ym", required=True)
    parser.add_argument("--holiday-csv", required=False)
    args = parser.parse_args()

    holidays = load_holidays(args.holiday_csv)
    engine = make_engine(args.db_url)

    for use_ym in ym_range(args.start_ym, args.end_ym):
        counts = build_month_counts(use_ym, holidays)
        upsert_month_count(engine, use_ym, counts)
        print(use_ym, counts)


if __name__ == "__main__":
    main()
