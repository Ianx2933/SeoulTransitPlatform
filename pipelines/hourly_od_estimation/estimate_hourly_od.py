"""
Estimate hourly OD records from daily OD and day-type hourly ratios.
"""

from __future__ import annotations

import argparse
import pandas as pd
from sqlalchemy import text

from common import day_type_from_yyyymmdd, load_holidays, make_engine, normalize_ars


# Fallback ratio used when a route-stop-day_type ratio is unavailable.
DEFAULT_RATIO = [
    0.01, 0.005, 0.003, 0.002, 0.01, 0.04,
    0.08, 0.11, 0.09, 0.05, 0.04, 0.04,
    0.05, 0.05, 0.05, 0.06, 0.08, 0.10,
    0.09, 0.06, 0.04, 0.025, 0.015, 0.005
]


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


def load_ratio_map(engine) -> dict[tuple[str, str, str], list[float]]:
    """
    Load ratio table into an in-memory lookup map.

    The lookup key is (route_no, origin_ars, day_type).
    This avoids repeated DB queries for every OD row.
    """
    df = pd.read_sql("""
        SELECT route_no, stop_ars, day_type, hour, boarding_ratio
        FROM dow_hourly_ratio
    """, engine)

    ratio_map = {}

    if df.empty:
        return ratio_map

    for (route_no, stop_ars, day_type), group in df.groupby(["route_no", "stop_ars", "day_type"]):
        ratios = [0.0] * 24
        for _, row in group.iterrows():
            hour = int(row["hour"])
            if 0 <= hour <= 23:
                ratios[hour] = float(row["boarding_ratio"])

        ratio_map[(str(route_no).strip(), normalize_ars(stop_ars), str(day_type))] = ratios

    return ratio_map


def save_estimates(engine, records: list[dict]) -> None:

    # Batch upsert estimated hourly OD records.
    if not records:
        return

    sql = text("""
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

    with engine.begin() as conn:
        conn.execute(sql, records)


def estimate(engine, daily_od: pd.DataFrame, ratio_map: dict, holidays: set[str], batch_size: int) -> int:
    """
    Apply hourly ratios to daily OD records.

    The origin stop's boarding ratio is used because the OD starts at the boarding stop.
    This is a transparent baseline assumption that can later be replaced by LSTM
    or more detailed trip-chain inference.
    """
    buffer = []
    total = 0

    for _, row in daily_od.iterrows():
        date = str(row["기준일자"])
        route_no = str(row["노선명"]).strip()
        origin_ars = normalize_ars(row["승차_정류장ars"])
        destination_ars = normalize_ars(row["하차_정류장ars"])
        day_type = day_type_from_yyyymmdd(date, holidays)
        daily_passengers = float(row["daily_passengers"])

        ratios = ratio_map.get((route_no, origin_ars, day_type), DEFAULT_RATIO)
        method = "dow_least_squares_sun_holiday"
        if ratios == DEFAULT_RATIO:
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
            save_estimates(engine, buffer)
            total += len(buffer)
            print(f"Inserted/updated {total} hourly OD rows")
            buffer.clear()

    if buffer:
        save_estimates(engine, buffer)
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
    args = parser.parse_args()

    engine = make_engine(args.db_url)
    holidays = load_holidays(args.holiday_csv)

    daily_od = load_daily_od(engine, args.start_date, args.end_date, args.limit)
    ratio_map = load_ratio_map(engine)

    print(f"Daily OD rows: {len(daily_od)}")
    print(f"Ratio keys: {len(ratio_map)}")

    total = estimate(engine, daily_od, ratio_map, holidays, args.batch_size)
    print(f"Done. Inserted/updated estimated_hourly_od rows: {total}")


if __name__ == "__main__":
    main()
