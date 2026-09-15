"""
Estimate hourly OD records from daily OD and day-type hourly ratios.
"""

from __future__ import annotations

import argparse
import numpy as np
import pandas as pd
from sqlalchemy import text

from common import day_type_from_yyyymmdd, load_holidays, make_engine, normalize_ars


# Fallback ratio used when a route-stop-day_type ratio is unavailable.
# The hand-written profile summed to 1.105, which inflated fallback estimates
# by 10.5%. It is normalized so the 24 values distribute exactly the daily total.
_RAW_DEFAULT_RATIO = [
    0.01, 0.005, 0.003, 0.002, 0.01, 0.04,
    0.08, 0.11, 0.09, 0.05, 0.04, 0.04,
    0.05, 0.05, 0.05, 0.06, 0.08, 0.10,
    0.09, 0.06, 0.04, 0.025, 0.015, 0.005
]
DEFAULT_RATIO = [value / sum(_RAW_DEFAULT_RATIO) for value in _RAW_DEFAULT_RATIO]


def load_daily_od(engine, start_date: str | None, end_date: str | None, limit: int | None) -> pd.DataFrame:
    """
    Load daily OD records from analysis_table_final.

    The source table contains origin-destination but no hour.
    This function aggregates duplicate OD rows before hourly decomposition.
    """
    conditions = ["승차_정류장ars != '00000'", "하차_정류장ars != '00000'"]
    params = {}

    if start_date:
        conditions.append("기준일자 >= :start_date")
        params["start_date"] = start_date

    if end_date:
        conditions.append("기준일자 <= :end_date")
        params["end_date"] = end_date

    where_clause = " AND ".join(conditions)
    limit_clause = f" LIMIT {int(limit)}" if limit else ""

    sql = text(f"""
        SELECT
            기준일자,
            노선명,
            승차_정류장ars,
            하차_정류장ars,
            SUM(CAST(승객수 AS DOUBLE PRECISION)) AS daily_passengers
        FROM analysis_table_final
        WHERE {where_clause}
        GROUP BY 기준일자, 노선명, 승차_정류장ars, 하차_정류장ars
        {limit_clause}
    """)

    return pd.read_sql(sql, engine, params=params)


def load_ratio_map(engine, day_types: list[str] | None = None) -> dict[tuple[str, str, str], list[float]]:
    """
    Load ratio table into an in-memory lookup map.

    The lookup key is (route_no, origin_ars, day_type).
    Only the day types needed for the requested dates are read, which keeps
    memory at roughly one seventh of the full table for a single date.
    """
    day_filter = "WHERE day_type = ANY(:day_types)" if day_types else ""
    params = {"day_types": list(day_types)} if day_types else {}
    df = pd.read_sql(text(f"""
        SELECT route_no, stop_ars, day_type, hour, boarding_ratio
        FROM dow_hourly_ratio
        {day_filter}
    """), engine, params=params)

    if df.empty:
        return {}
    df = df.reset_index(drop=True)

    grouped = df.groupby(["route_no", "stop_ars", "day_type"], sort=True)
    gid = grouped.ngroup().to_numpy()
    hours = pd.to_numeric(df["hour"]).astype(int).to_numpy()
    ratios = df["boarding_ratio"].astype(float).to_numpy()

    matrix = np.zeros((int(gid.max()) + 1, 24))
    in_range = (hours >= 0) & (hours <= 23)
    matrix[gid[in_range], hours[in_range]] = ratios[in_range]

    _, first_row = np.unique(gid, return_index=True)
    keys = df.loc[first_row, ["route_no", "stop_ars", "day_type"]].to_numpy()

    ratio_map = {}
    for index, (route_no, stop_ars, day_type) in enumerate(keys):
        ratio_map[(str(route_no).strip(), normalize_ars(stop_ars), str(day_type))] = matrix[index].tolist()
    return ratio_map


INSERT_SQL = text("""
    INSERT INTO estimated_hourly_od (
        기준일자, 노선명, 승차_정류장ars, 하차_정류장ars,
        hour, estimated_passengers, method
    )
    VALUES (
        :기준일자, :노선명, :승차_정류장ars, :하차_정류장ars,
        :hour, :estimated_passengers, :method
    )
    ON CONFLICT (기준일자, 노선명, 승차_정류장ars, 하차_정류장ars, hour, method)
    DO UPDATE SET
        estimated_passengers = EXCLUDED.estimated_passengers
""")


def save_estimates(engine, records: list[dict], conn=None) -> None:
    """
    Batch upsert estimated hourly OD records.

    With conn, rows join that open transaction; otherwise each batch commits on its own.
    """
    if not records:
        return
    if conn is not None:
        conn.execute(INSERT_SQL, records)
        return
    with engine.begin() as own_conn:
        own_conn.execute(INSERT_SQL, records)


def estimate(engine, daily_od: pd.DataFrame, ratio_map: dict, holidays: set[str], batch_size: int,
             conn=None) -> int:
    """
    Apply hourly ratios to daily OD records.

    The origin stop's boarding ratio is used because the OD starts at the boarding stop.
    This is a transparent baseline assumption that can later be replaced by LSTM
    or more detailed trip-chain inference.
    """
    buffer = []
    total = 0
    next_report = 500_000
    day_type_cache: dict[str, str] = {}

    columns = zip(
        daily_od["기준일자"], daily_od["노선명"], daily_od["승차_정류장ars"],
        daily_od["하차_정류장ars"], daily_od["daily_passengers"],
    )
    for raw_date, raw_route, raw_origin, raw_destination, raw_passengers in columns:
        date = str(raw_date)
        route_no = str(raw_route).strip()
        origin_ars = normalize_ars(raw_origin)
        destination_ars = normalize_ars(raw_destination)
        day_type = day_type_cache.get(date)
        if day_type is None:
            day_type = day_type_from_yyyymmdd(date, holidays)
            day_type_cache[date] = day_type
        daily_passengers = float(raw_passengers)

        ratios = ratio_map.get((route_no, origin_ars, day_type))
        method = "dow_least_squares_sun_holiday"
        if ratios is None:
            ratios = DEFAULT_RATIO
            method = "default_static_ratio"

        for hour, ratio in enumerate(ratios):
            buffer.append({
                "기준일자": date,
                "노선명": route_no,
                "승차_정류장ars": origin_ars,
                "하차_정류장ars": destination_ars,
                "hour": hour,
                "estimated_passengers": daily_passengers * float(ratio),
                "method": method,
            })

        if len(buffer) >= batch_size:
            save_estimates(engine, buffer, conn)
            total += len(buffer)
            buffer.clear()
            if total >= next_report:
                print(f"Inserted/updated {total:,} hourly OD rows")
                next_report += 500_000

    if buffer:
        save_estimates(engine, buffer, conn)
        total += len(buffer)

    return total


def main():
    # CLI entry point for hourly OD estimation.
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--start-date", required=False, help="YYYYMMDD")
    parser.add_argument("--end-date", required=False, help="YYYYMMDD")
    parser.add_argument("--holiday-csv", required=False)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--batch-size", type=int, default=5000)
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Delete existing estimates in the date range first, in the same transaction. "
             "Needed after ratios change, because method is part of the key and a row "
             "that switches method would otherwise be kept twice.",
    )
    args = parser.parse_args()

    if args.replace and not (args.start_date and args.end_date):
        raise SystemExit("--replace needs both --start-date and --end-date.")
    if args.replace and args.limit:
        raise SystemExit("--replace cannot be combined with --limit.")

    engine = make_engine(args.db_url)
    holidays = load_holidays(args.holiday_csv)

    daily_od = load_daily_od(engine, args.start_date, args.end_date, args.limit)
    day_types = sorted({day_type_from_yyyymmdd(str(d), holidays) for d in daily_od["기준일자"].unique()})
    ratio_map = load_ratio_map(engine, day_types) if day_types else {}

    print(f"Daily OD rows: {len(daily_od):,}")
    print(f"Day types: {day_types}")
    print(f"Ratio keys: {len(ratio_map):,}")

    if not args.replace:
        total = estimate(engine, daily_od, ratio_map, holidays, args.batch_size)
        print(f"Done. Inserted/updated estimated_hourly_od rows: {total:,}")
        return

    with engine.begin() as conn:
        deleted = conn.execute(
            text("DELETE FROM estimated_hourly_od WHERE 기준일자 >= :start_date AND 기준일자 <= :end_date"),
            {"start_date": args.start_date, "end_date": args.end_date},
        ).rowcount
        print(f"Deleted existing rows in range: {deleted:,}")
        total = estimate(engine, daily_od, ratio_map, holidays, args.batch_size, conn=conn)
    print(f"Done. Inserted estimated_hourly_od rows: {total:,}")


if __name__ == "__main__":
    main()
