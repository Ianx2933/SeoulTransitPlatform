"""Solve day-type hourly boarding ratios from monthly aggregate API data.

For each route-stop-hour, this script solves:

    A x = b

A = monthly day-type counts
x = average boarding passengers for each day type
b = monthly hourly boarding passengers

Routes are processed in batches so memory stays bounded as months are added.
Groups that observed the same set of months share the same A, so they are
solved together in one least-squares call. The result is identical to
solving each group separately.
"""

from __future__ import annotations

import argparse

import numpy as np
import pandas as pd
from sqlalchemy import text

from .common import DAY_TYPES, make_engine


KEY_COLUMNS = ["route_no", "stop_ars", "hour"]


def load_month_counts(engine) -> pd.DataFrame:
    """
    Load the A-matrix source table.
    """
    return pd.read_sql("""
        SELECT use_ym, mon, tue, wed, thu, fri, sat, sun_holiday
        FROM month_day_count
        ORDER BY use_ym
    """, engine)


def load_route_list(engine) -> list[str]:
    """
    List routes so the monthly table can be read in bounded batches.
    """
    df = pd.read_sql("""
        SELECT DISTINCT route_no
        FROM hourly_bus_stop_passenger
        ORDER BY route_no
    """, engine)
    return df["route_no"].tolist()


def load_hourly_monthly(engine, routes: list[str] | None = None) -> pd.DataFrame:
    """
    Load monthly hourly boarding counts by route-stop-hour.

    Only 5-digit ARS codes are kept. Virtual stops arrive as '~' and blank codes
    normalize to '00000'; both would distort the decomposition model.
    """
    route_filter = "AND route_no = ANY(:routes)" if routes is not None else ""
    sql = text(f"""
        SELECT
            use_ym,
            route_no,
            stop_ars,
            hour,
            SUM(boarding_passengers) AS monthly_boarding
        FROM hourly_bus_stop_passenger
        WHERE stop_ars ~ '^[0-9][0-9][0-9][0-9][0-9]$'
          AND stop_ars <> '00000'
          {route_filter}
        GROUP BY use_ym, route_no, stop_ars, hour
    """)
    params = {"routes": list(routes)} if routes is not None else {}
    return pd.read_sql(sql, engine, params=params)


def nonnegative_lstsq(A: np.ndarray, b: np.ndarray) -> np.ndarray:
    """
    Solve least squares and clip negative estimates to zero.

    b may be a vector or a matrix with one column per group.
    """
    x, *_ = np.linalg.lstsq(A, b, rcond=None)
    return np.clip(x, 0, None)


def solve_grouped(month_counts: pd.DataFrame, hourly: pd.DataFrame, min_months: int,
                  value_columns: list[str]):
    """
    Solve A x = b for every route-stop-hour group without a Python loop per group.

    Returns (keys, solutions) where keys holds one row per solved group and
    solutions maps each value column to an array of shape (groups, day types).
    """
    months = month_counts["use_ym"].astype(str).str.strip()
    if months.duplicated().any():
        raise ValueError("month_day_count has duplicate use_ym values")
    month_position = pd.Series(np.arange(len(months)), index=months.to_numpy())
    A_all = month_counts[DAY_TYPES].astype(float).to_numpy()

    position = hourly["use_ym"].astype(str).str.strip().map(month_position)
    keep = position.notna().to_numpy()
    data = hourly.loc[keep].reset_index(drop=True)
    if data.empty:
        return None, None
    month_pos = position[keep].astype(int).to_numpy()

    gid = data.groupby(KEY_COLUMNS, sort=True).ngroup().to_numpy()
    n_groups, n_months = int(gid.max()) + 1, len(months)

    cell = gid.astype(np.int64) * n_months + month_pos
    if np.unique(cell).size != cell.size:
        raise ValueError("hourly data has more than one row per group and month")

    present = np.zeros((n_groups, n_months), dtype=bool)
    present[gid, month_pos] = True
    values = {}
    for column in value_columns:
        matrix = np.zeros((n_groups, n_months))
        matrix[gid, month_pos] = data[column].astype(float).to_numpy()
        values[column] = matrix

    _, first_row = np.unique(gid, return_index=True)
    keys = data.loc[first_row, KEY_COLUMNS].reset_index(drop=True)

    # Skip sparse groups because underdetermined estimates are unstable.
    valid = np.flatnonzero(present.sum(axis=1) >= min_months)
    solutions = {column: np.zeros((n_groups, len(DAY_TYPES))) for column in value_columns}

    if valid.size:
        patterns, inverse = np.unique(present[valid], axis=0, return_inverse=True)
        inverse = np.asarray(inverse).reshape(-1)
        for pattern_index, month_mask in enumerate(patterns):
            rows = valid[inverse == pattern_index]
            A = A_all[month_mask]
            for column in value_columns:
                b = values[column][rows][:, month_mask].T
                solutions[column][rows] = nonnegative_lstsq(A, b).T

    keys = keys.iloc[valid].reset_index(drop=True)
    solutions = {column: array[valid] for column, array in solutions.items()}
    return keys, solutions


def solve_patterns(month_counts: pd.DataFrame, hourly: pd.DataFrame, min_months: int) -> pd.DataFrame:
    """
    Estimate day-type hourly averages and convert them into ratios.
    """
    keys, solutions = solve_grouped(month_counts, hourly, min_months, ["monthly_boarding"])
    if keys is None or keys.empty:
        return pd.DataFrame()

    n_day_types = len(DAY_TYPES)
    avg_df = pd.DataFrame({
        "route_no": np.repeat(keys["route_no"].to_numpy(), n_day_types),
        "stop_ars": np.repeat(keys["stop_ars"].to_numpy(), n_day_types),
        "day_type": np.tile(np.array(DAY_TYPES, dtype=object), len(keys)),
        "hour": np.repeat(keys["hour"].astype(int).to_numpy(), n_day_types),
        "avg_boarding_passengers": solutions["monthly_boarding"].reshape(-1),
    })

    # Normalize 24 hourly averages within each route-stop-day_type into ratios.
    total_by_day = (
        avg_df.groupby(["route_no", "stop_ars", "day_type"])["avg_boarding_passengers"]
        .transform("sum")
    )

    avg_df["boarding_ratio"] = np.where(
        total_by_day > 0,
        avg_df["avg_boarding_passengers"] / total_by_day.where(total_by_day > 0, 1.0),
        0.0,
    )
    avg_df["method"] = "dow_least_squares_sun_holiday"

    return avg_df


UPSERT_SQL = text("""
    INSERT INTO dow_hourly_ratio (
        route_no, stop_ars, day_type, hour,
        avg_boarding_passengers, boarding_ratio, method
    )
    VALUES (
        :route_no, :stop_ars, :day_type, :hour,
        :avg_boarding_passengers, :boarding_ratio, :method
    )
    ON CONFLICT (route_no, stop_ars, day_type, hour)
    DO UPDATE SET
        avg_boarding_passengers = EXCLUDED.avg_boarding_passengers,
        boarding_ratio = EXCLUDED.boarding_ratio,
        method = EXCLUDED.method
""")


def upsert_ratios(conn, ratio_df: pd.DataFrame, batch_size: int) -> None:
    """
    Write ratio rows through an open connection in bounded batches.
    """
    if ratio_df.empty:
        return
    records = ratio_df.to_dict(orient="records")
    for start in range(0, len(records), batch_size):
        conn.execute(UPSERT_SQL, records[start:start + batch_size])


def save_ratios(engine, ratio_df: pd.DataFrame, batch_size: int = 50000) -> None:
    """
    Persist ratios in one transaction. Kept for callers of the previous version.
    """
    if ratio_df.empty:
        print("No ratios generated.")
        return
    with engine.begin() as conn:
        upsert_ratios(conn, ratio_df, batch_size)


def main():
    """
    CLI entry point for least-squares ratio estimation.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--min-months", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=50000)
    parser.add_argument("--route-batch", type=int, default=50,
                        help="Routes read and solved per batch. Lower it if memory is tight.")
    args = parser.parse_args()

    engine = make_engine(args.db_url)
    month_counts = load_month_counts(engine)
    routes = load_route_list(engine)

    print(f"Loaded month count rows: {len(month_counts)}")
    print(f"Routes: {len(routes)} (batch {args.route_batch})")

    total_input = 0
    total_output = 0

    # One transaction for all batches, so an interrupted run leaves the old ratios intact.
    with engine.begin() as conn:
        for start in range(0, len(routes), args.route_batch):
            batch = routes[start:start + args.route_batch]
            hourly = load_hourly_monthly(engine, batch)
            ratio_df = solve_patterns(month_counts, hourly, args.min_months)
            upsert_ratios(conn, ratio_df, args.batch_size)

            total_input += len(hourly)
            total_output += len(ratio_df)
            done = min(start + args.route_batch, len(routes))
            print(f"routes {done}/{len(routes)}: monthly hourly rows {total_input:,}; "
                  f"ratio rows {total_output:,}")

    print(f"Done. Generated ratio rows: {total_output:,}")


if __name__ == "__main__":
    main()
