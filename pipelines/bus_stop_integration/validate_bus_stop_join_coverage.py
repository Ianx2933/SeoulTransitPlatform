"""
Optionally validate old versus new bus-stop coordinate coverage in PostgreSQL.

Set environment variables:
- PGHOST, PGPORT, PGDATABASE, PGUSER, PGPASSWORD

This script only reads from the database and writes CSV reports.
"""

import os
from pathlib import Path
import pandas as pd
from sqlalchemy import create_engine, text
from config import OUTPUT_LOCATION_DIR


def get_engine():
    """Create a SQLAlchemy engine from PostgreSQL environment variables."""
    host = os.getenv("PGHOST", "localhost")
    port = os.getenv("PGPORT", "5432")
    db = os.getenv("PGDATABASE")
    user = os.getenv("PGUSER", "postgres")
    password = os.getenv("PGPASSWORD", "")
    if not db:
        raise EnvironmentError("Set PGDATABASE before running this script.")
    return create_engine(f"postgresql+psycopg2://{user}:{password}@{host}:{port}/{db}")


def main() -> None:
    """Write summary and route-level validation reports."""
    OUTPUT_LOCATION_DIR.mkdir(parents=True, exist_ok=True)
    engine = get_engine()

    sql = """
    SELECT
        COUNT(*) AS total_bus_rows,
        COUNT(old_b."정류장번호") AS old_matched_rows,
        COUNT(new_b.canonical_node_id) AS new_matched_rows,
        COUNT(*) - COUNT(old_b."정류장번호") AS old_missing_rows,
        COUNT(*) - COUNT(new_b.canonical_node_id) AS new_missing_rows
    FROM integrated_hourly_transit_demand_light d
    LEFT JOIN bus_stop_location old_b
      ON LPAD(d.node_id::text, 5, '0') = LPAD(old_b."정류장번호"::text, 5, '0')
    LEFT JOIN bus_stop_demand_node_mapping m
      ON d.service_id = m.service_id
     AND d.node_id = m.demand_node_id
    LEFT JOIN integrated_bus_stop_location new_b
      ON m.canonical_node_id = new_b.canonical_node_id
    WHERE d.mode = 'bus';
    """

    summary = pd.read_sql_query(text(sql), engine)
    summary.to_csv(OUTPUT_LOCATION_DIR / "validate_new_bus_stop_join_summary.csv", index=False, encoding="utf-8-sig")
    print(summary.to_string(index=False))

    route_sql = """
    SELECT
        d.service_id,
        COUNT(*) AS total_rows,
        COUNT(new_b.canonical_node_id) AS new_matched_rows,
        COUNT(*) - COUNT(new_b.canonical_node_id) AS new_missing_rows,
        ROUND(((COUNT(*) - COUNT(new_b.canonical_node_id))::numeric / COUNT(*)) * 100, 2) AS new_missing_rate_percent
    FROM integrated_hourly_transit_demand_light d
    LEFT JOIN bus_stop_demand_node_mapping m
      ON d.service_id = m.service_id
     AND d.node_id = m.demand_node_id
    LEFT JOIN integrated_bus_stop_location new_b
      ON m.canonical_node_id = new_b.canonical_node_id
    WHERE d.mode = 'bus'
    GROUP BY d.service_id
    HAVING COUNT(*) - COUNT(new_b.canonical_node_id) > 0
    ORDER BY new_missing_rate_percent DESC, new_missing_rows DESC;
    """
    routes = pd.read_sql_query(text(route_sql), engine)
    routes.to_csv(OUTPUT_LOCATION_DIR / "validate_new_bus_stop_join_missing_routes.csv", index=False, encoding="utf-8-sig")
    print(f"Saved route report: {OUTPUT_LOCATION_DIR / 'validate_new_bus_stop_join_missing_routes.csv'}")


if __name__ == "__main__":
    main()
