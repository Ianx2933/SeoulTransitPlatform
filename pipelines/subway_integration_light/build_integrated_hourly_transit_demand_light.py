"""
Build lightweight integrated bus + subway hourly transit demand.

Each source loader returns the same canonical schema, so integration is a simple concat.
"""

from __future__ import annotations

import argparse

import pandas as pd
from sqlalchemy import text

from common import make_engine


def load_bus_demand(engine) -> pd.DataFrame:
    """
    Load bus stop demand from Phase 6.3 Local output.
    """
    return pd.read_sql("""
        SELECT
            'bus' AS mode,
            route_no AS service_id,
            stop_ars AS node_id,
            stop_ars AS node_name,
            day_type,
            hour,
            estimated_boarding AS boarding,
            estimated_alighting AS alighting,
            'estimated_hourly_stop_demand' AS source
        FROM estimated_hourly_stop_demand
    """, engine)


def load_subway_demand(engine) -> pd.DataFrame:
    """
    Load lightweight subway station demand.
    """
    return pd.read_sql("""
        SELECT
            'subway' AS mode,
            line_name AS service_id,
            station_name AS node_id,
            station_name AS node_name,
            day_type,
            hour,
            boarding,
            alighting,
            'subway_hourly_station_demand_light' AS source
        FROM subway_hourly_station_demand_light
    """, engine)


def save_integrated(engine, integrated: pd.DataFrame, batch_size: int) -> None:
    """
    Save integrated rows using batch upsert.
    """
    if integrated.empty:
        print("No integrated rows generated.")
        return

    sql = text("""
        INSERT INTO integrated_hourly_transit_demand_light (
            mode,
            service_id,
            node_id,
            node_name,
            day_type,
            hour,
            boarding,
            alighting,
            source
        )
        VALUES (
            :mode,
            :service_id,
            :node_id,
            :node_name,
            :day_type,
            :hour,
            :boarding,
            :alighting,
            :source
        )
        ON CONFLICT (mode, service_id, node_id, day_type, hour)
        DO UPDATE SET
            node_name = EXCLUDED.node_name,
            boarding = EXCLUDED.boarding,
            alighting = EXCLUDED.alighting,
            source = EXCLUDED.source
    """)

    records = integrated.to_dict(orient="records")
    total = len(records)

    with engine.begin() as conn:
        for start in range(0, total, batch_size):
            batch = records[start:start + batch_size]
            conn.execute(sql, batch)
            print(f"{min(start + batch_size, total)}/{total} integrated rows saved")


def main():
    """
    CLI entry point.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--batch-size", type=int, default=50000)
    args = parser.parse_args()

    engine = make_engine(args.db_url)

    bus = load_bus_demand(engine)
    subway = load_subway_demand(engine)

    print(f"Loaded bus rows: {len(bus)}")
    print(f"Loaded subway rows: {len(subway)}")

    integrated = pd.concat([bus, subway], ignore_index=True)
    print(f"Generated integrated rows: {len(integrated)}")

    save_integrated(engine, integrated, args.batch_size)

    print("Done building lightweight integrated transit demand.")


if __name__ == "__main__":
    main()
