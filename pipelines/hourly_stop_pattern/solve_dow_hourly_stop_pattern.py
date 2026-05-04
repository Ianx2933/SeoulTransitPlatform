"""
Solve day-type hourly stop patterns for boarding and alighting.

For each route-stop-hour, this script solves two least-squares problems:

    A x_boarding = b_boarding
    A x_alighting = b_alighting

A is the month-day-count matrix, and b is the monthly hourly passenger count.
"""

from __future__ import annotations

import argparse

import numpy as np
import pandas as pd
from sqlalchemy import text

from common import DAY_TYPES, make_engine


def load_month_counts(engine) -> pd.DataFrame:
    """
    Load month-day-count rows used as the least-squares design matrix.
    """
    return pd.read_sql("""
        SELECT use_ym, mon, tue, wed, thu, fri, sat, sun_holiday
        FROM month_day_count
        ORDER BY use_ym
    """, engine)


def load_hourly_monthly(engine) -> pd.DataFrame:
    """
    Load monthly hourly boarding and alighting counts by route-stop-hour.

    Invalid virtual stop ARS '00000' is excluded because it does not represent
    a real physical stop.
    """
    return pd.read_sql("""
        SELECT
            use_ym,
            route_no,
            stop_ars,
            hour,
            SUM(boarding_passengers) AS monthly_boarding,
            SUM(alighting_passengers) AS monthly_alighting
        FROM hourly_bus_stop_passenger
        WHERE stop_ars != '00000'
        GROUP BY use_ym, route_no, stop_ars, hour
    """, engine)


def nonnegative_lstsq(A: np.ndarray, b: np.ndarray) -> np.ndarray:
    """
    Solve least squares and clip negative passenger estimates to zero.
    """
    x, *_ = np.linalg.lstsq(A, b, rcond=None)
    return np.clip(x, 0, None)


def solve_patterns(month_counts: pd.DataFrame, hourly: pd.DataFrame, min_months: int) -> pd.DataFrame:
    """
    Estimate boarding/alighting averages and ratios for each day type.
    """
    merged = hourly.merge(month_counts, on="use_ym", how="inner")
    if merged.empty:
        return pd.DataFrame()

    results = []

    for (route_no, stop_ars, hour), group in merged.groupby(["route_no", "stop_ars", "hour"]):
        # Skip unstable groups with too few months.
        if group["use_ym"].nunique() < min_months:
            continue

        A = group[DAY_TYPES].astype(float).to_numpy()
        boarding_b = group["monthly_boarding"].astype(float).to_numpy()
        alighting_b = group["monthly_alighting"].astype(float).to_numpy()

        boarding_x = nonnegative_lstsq(A, boarding_b)
        alighting_x = nonnegative_lstsq(A, alighting_b)

        for day_type, avg_boarding, avg_alighting in zip(DAY_TYPES, boarding_x, alighting_x):
            results.append({
                "route_no": str(route_no).strip(),
                "stop_ars": str(stop_ars).strip().zfill(5),
                "day_type": day_type,
                "hour": int(hour),
                "avg_boarding_passengers": float(avg_boarding),
                "avg_alighting_passengers": float(avg_alighting),
            })

    if not results:
        return pd.DataFrame()

    pattern_df = pd.DataFrame(results)

    boarding_total = (
        pattern_df.groupby(["route_no", "stop_ars", "day_type"])["avg_boarding_passengers"]
        .transform("sum")
    )
    alighting_total = (
        pattern_df.groupby(["route_no", "stop_ars", "day_type"])["avg_alighting_passengers"]
        .transform("sum")
    )

    # Normalize 24 hourly values into ratios.
    pattern_df["boarding_ratio"] = np.where(
        boarding_total > 0,
        pattern_df["avg_boarding_passengers"] / boarding_total,
        0.0,
    )
    pattern_df["alighting_ratio"] = np.where(
        alighting_total > 0,
        pattern_df["avg_alighting_passengers"] / alighting_total,
        0.0,
    )

    pattern_df["method"] = "dow_least_squares_sun_holiday"

    return pattern_df


def save_patterns(engine, pattern_df: pd.DataFrame, batch_size: int) -> None:
    """
    Save pattern rows using batch upsert.
    """
    if pattern_df.empty:
        print("No stop patterns generated.")
        return

    sql = text("""
        INSERT INTO dow_hourly_stop_pattern (
            route_no,
            stop_ars,
            day_type,
            hour,
            avg_boarding_passengers,
            avg_alighting_passengers,
            boarding_ratio,
            alighting_ratio,
            method
        )
        VALUES (
            :route_no,
            :stop_ars,
            :day_type,
            :hour,
            :avg_boarding_passengers,
            :avg_alighting_passengers,
            :boarding_ratio,
            :alighting_ratio,
            :method
        )
        ON CONFLICT (route_no, stop_ars, day_type, hour)
        DO UPDATE SET
            avg_boarding_passengers = EXCLUDED.avg_boarding_passengers,
            avg_alighting_passengers = EXCLUDED.avg_alighting_passengers,
            boarding_ratio = EXCLUDED.boarding_ratio,
            alighting_ratio = EXCLUDED.alighting_ratio,
            method = EXCLUDED.method
    """)

    records = pattern_df.to_dict(orient="records")
    total = len(records)

    with engine.begin() as conn:
        for start in range(0, total, batch_size):
            batch = records[start:start + batch_size]
            conn.execute(sql, batch)
            print(f"{min(start + batch_size, total)}/{total} stop pattern rows saved")


def main():
    """
    CLI entry point.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--min-months", type=int, default=4)
    parser.add_argument("--batch-size", type=int, default=50000)
    args = parser.parse_args()

    engine = make_engine(args.db_url)

    month_counts = load_month_counts(engine)
    hourly = load_hourly_monthly(engine)

    print(f"Loaded month count rows: {len(month_counts)}")
    print(f"Loaded monthly hourly rows: {len(hourly)}")

    pattern_df = solve_patterns(month_counts, hourly, args.min_months)

    print(f"Generated stop pattern rows before save: {len(pattern_df)}")

    save_patterns(engine, pattern_df, args.batch_size)

    print(f"Generated stop pattern rows: {len(pattern_df)}")


if __name__ == "__main__":
    main()
