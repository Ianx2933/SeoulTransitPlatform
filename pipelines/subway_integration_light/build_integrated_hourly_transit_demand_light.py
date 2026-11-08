"""
Build lightweight integrated bus + subway hourly transit demand.

Each source loader returns the same canonical schema, so integration is a simple concat.
"""

from __future__ import annotations

import argparse
import io
import os

import pandas as pd
from sqlalchemy import text

from common import make_engine


INTEGRATED_TABLE = "integrated_hourly_transit_demand_light"
INTEGRATED_COLUMNS = ["mode", "service_id", "node_id", "node_name", "day_type", "hour",
                      "boarding", "alighting", "source"]

# Same contract as table_target / prepare_next_table / copy_frame in
# pipelines/hourly_od_estimation/common.py; this package's common module is
# separate, so the few lines are repeated here rather than imported across.
TABLE_TARGET_ENV = "PIPELINE_TABLE_TARGET"
NEXT_SUFFIX = "__next"


def table_target(value: str | None = None) -> str:
    target = (value or os.environ.get(TABLE_TARGET_ENV) or "live").strip().lower()
    if target not in ("live", "next"):
        raise SystemExit(f"{TABLE_TARGET_ENV} must be 'live' or 'next', not {target!r}")
    return target


def target_table(table: str, target: str) -> str:
    return table + NEXT_SUFFIX if target == "next" else table


def write_next(engine, integrated: pd.DataFrame, chunk_rows: int = 500_000) -> str:
    """Fill a fresh <table>__next with COPY; publish_rebuild swaps it in after validation."""
    nxt = INTEGRATED_TABLE + NEXT_SUFFIX
    cols = ", ".join(INTEGRATED_COLUMNS)
    with engine.begin() as conn:
        conn.exec_driver_sql(f"DROP TABLE IF EXISTS {nxt}")
        conn.exec_driver_sql(f"CREATE TABLE {nxt} (LIKE {INTEGRATED_TABLE} INCLUDING ALL)")
        cursor = conn.connection.cursor()
        try:
            for start in range(0, len(integrated), chunk_rows):
                buffer = io.StringIO()
                integrated.iloc[start:start + chunk_rows][INTEGRATED_COLUMNS].to_csv(
                    buffer, index=False, header=False)
                buffer.seek(0)
                cursor.copy_expert(f"COPY {nxt} ({cols}) FROM STDIN WITH (FORMAT csv)", buffer)
        finally:
            cursor.close()
    with engine.begin() as conn:
        conn.execute(text(f"ANALYZE {nxt}"))
    return nxt


def load_bus_demand(engine, table: str = "estimated_hourly_stop_demand") -> pd.DataFrame:
    """
    Load bus stop demand from Phase 6.3 Local output.
    """
    return pd.read_sql(f"""
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
        FROM {table}
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
    parser.add_argument(
        "--db-url",
        default=os.environ.get("PIPELINE_DB_URL"),
        required="PIPELINE_DB_URL" not in os.environ,
        help="SQLAlchemy URL. Defaults to PIPELINE_DB_URL, so a scheduler can pass "
             "it from a secret instead of a visible argument.",
    )
    parser.add_argument("--batch-size", type=int, default=50000)
    parser.add_argument("--target", choices=["live", "next"], default=None,
                        help="Write the table itself (live) or a copy for publish_rebuild "
                             "(next), reading bus demand from the same target. "
                             "Defaults to PIPELINE_TABLE_TARGET, else live.")
    args = parser.parse_args()

    engine = make_engine(args.db_url)
    target = table_target(args.target)

    bus = load_bus_demand(engine, target_table("estimated_hourly_stop_demand", target))
    subway = load_subway_demand(engine)

    print(f"Loaded bus rows: {len(bus)}")
    print(f"Loaded subway rows: {len(subway)}")

    integrated = pd.concat([bus, subway], ignore_index=True)
    print(f"Generated integrated rows: {len(integrated)}")

    if target == "next":
        print(f"Wrote {write_next(engine, integrated)}")
    else:
        save_integrated(engine, integrated, args.batch_size)

    print("Done building lightweight integrated transit demand.")


if __name__ == "__main__":
    main()
