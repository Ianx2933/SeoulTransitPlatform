"""
Create estimated hourly stop demand from day-type hourly patterns.

This script materializes the stop-level boarding/alighting demand that can be used
for vehicle requirement, headway, and congestion estimation.
"""

from __future__ import annotations

import argparse

import pandas as pd
from sqlalchemy import text

from common import make_engine


def load_patterns(engine) -> pd.DataFrame:
    """
    Load solved hourly stop patterns.
    """
    return pd.read_sql("""
        SELECT
            route_no,
            stop_ars,
            day_type,
            hour,
            avg_boarding_passengers,
            avg_alighting_passengers
        FROM dow_hourly_stop_pattern
    """, engine)


def save_stop_demand(engine, demand_df: pd.DataFrame, batch_size: int) -> None:
    """
    Save estimated hourly stop demand using batch upsert.
    """
    if demand_df.empty:
        print("No stop demand rows generated.")
        return

    sql = text("""
        INSERT INTO estimated_hourly_stop_demand (
            route_no,
            stop_ars,
            day_type,
            hour,
            estimated_boarding,
            estimated_alighting,
            method
        )
        VALUES (
            :route_no,
            :stop_ars,
            :day_type,
            :hour,
            :estimated_boarding,
            :estimated_alighting,
            :method
        )
        ON CONFLICT (route_no, stop_ars, day_type, hour)
        DO UPDATE SET
            estimated_boarding = EXCLUDED.estimated_boarding,
            estimated_alighting = EXCLUDED.estimated_alighting,
            method = EXCLUDED.method
    """)

    records = demand_df.to_dict(orient="records")
    total = len(records)

    with engine.begin() as conn:
        for start in range(0, total, batch_size):
            batch = records[start:start + batch_size]
            conn.execute(sql, batch)
            print(f"{min(start + batch_size, total)}/{total} stop demand rows saved")


def build_stop_demand(patterns: pd.DataFrame) -> pd.DataFrame:
    """
    Convert solved averages into the stop demand serving table.

    In this local phase, the estimated demand equals the least-squares average.
    """
    if patterns.empty:
        return pd.DataFrame()

    demand_df = patterns.rename(columns={
        "avg_boarding_passengers": "estimated_boarding",
        "avg_alighting_passengers": "estimated_alighting",
    }).copy()

    demand_df["method"] = "dow_least_squares_sun_holiday"

    return demand_df[
        [
            "route_no",
            "stop_ars",
            "day_type",
            "hour",
            "estimated_boarding",
            "estimated_alighting",
            "method",
        ]
    ]


def main():
    """
    CLI entry point.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--batch-size", type=int, default=50000)
    args = parser.parse_args()

    engine = make_engine(args.db_url)

    patterns = load_patterns(engine)
    print(f"Loaded stop pattern rows: {len(patterns)}")

    demand_df = build_stop_demand(patterns)
    print(f"Generated stop demand rows before save: {len(demand_df)}")

    save_stop_demand(engine, demand_df, args.batch_size)

    print(f"Generated stop demand rows: {len(demand_df)}")


if __name__ == "__main__":
    main()
