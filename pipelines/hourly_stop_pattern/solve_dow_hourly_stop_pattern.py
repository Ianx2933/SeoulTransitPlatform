"""
Solve day-type hourly stop patterns for boarding and alighting.

For each route-stop-hour, this script solves two least-squares problems:

    A x_boarding = b_boarding
    A x_alighting = b_alighting

A is the month-day-count matrix, and b is the monthly hourly passenger count.

Routes are processed in batches so memory stays bounded as months are added.
Groups that observed the same set of months share the same A and are solved
together in one least-squares call, which gives the same result as solving
each group separately.
"""

from __future__ import annotations

import argparse

import numpy as np
import pandas as pd
from sqlalchemy import text

from common import DAY_TYPES, make_engine


KEY_COLUMNS = ["route_no", "stop_ars", "hour"]
VALUE_COLUMNS = ["monthly_boarding", "monthly_alighting"]


def load_month_counts(engine) -> pd.DataFrame:
    """
    Load month-day-count rows used as the least-squares design matrix.
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
    Load monthly hourly boarding and alighting counts by route-stop-hour.

    Only 5-digit ARS codes are kept. Virtual stops arrive as '~' and blank
    codes normalize to '00000'; neither is a real physical stop.
    """
    route_filter = "AND route_no = ANY(:routes)" if routes is not None else ""
    sql = text(f"""
        SELECT
            use_ym,
            route_no,
            stop_ars,
            hour,
            SUM(boarding_passengers) AS monthly_boarding,
            SUM(alighting_passengers) AS monthly_alighting
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
    Solve least squares and clip negative passenger estimates to zero.
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

    # Skip unstable groups with too few months.
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
    Estimate boarding/alighting averages and ratios for each day type.
    """
    keys, solutions = solve_grouped(month_counts, hourly, min_months, VALUE_COLUMNS)
    if keys is None or keys.empty:
        return pd.DataFrame()

    n_day_types = len(DAY_TYPES)
    route_no = keys["route_no"].astype(str).str.strip().to_numpy()
    stop_ars = keys["stop_ars"].astype(str).str.strip().str.zfill(5).to_numpy()

    pattern_df = pd.DataFrame({
        "route_no": np.repeat(route_no, n_day_types),
        "stop_ars": np.repeat(stop_ars, n_day_types),
        "day_type": np.tile(np.array(DAY_TYPES, dtype=object), len(keys)),
        "hour": np.repeat(keys["hour"].astype(int).to_numpy(), n_day_types),
        "avg_boarding_passengers": solutions["monthly_boarding"].reshape(-1),
        "avg_alighting_passengers": solutions["monthly_alighting"].reshape(-1),
    })

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
        pattern_df["avg_boarding_passengers"] / boarding_total.where(boarding_total > 0, 1.0),
        0.0,
    )
    pattern_df["alighting_ratio"] = np.where(
        alighting_total > 0,
        pattern_df["avg_alighting_passengers"] / alighting_total.where(alighting_total > 0, 1.0),
        0.0,
    )

    pattern_df["method"] = "dow_least_squares_sun_holiday"

    return pattern_df


UPSERT_SQL = text("""
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


def upsert_patterns(conn, pattern_df: pd.DataFrame, batch_size: int) -> None:
    """
    Write pattern rows through an open connection in bounded batches.
    """
    if pattern_df.empty:
        return
    records = pattern_df.to_dict(orient="records")
    for start in range(0, len(records), batch_size):
        conn.execute(UPSERT_SQL, records[start:start + batch_size])


def save_patterns(engine, pattern_df: pd.DataFrame, batch_size: int) -> None:
    """
    Save pattern rows in one transaction. Kept for callers of the previous version.
    """
    if pattern_df.empty:
        print("No stop patterns generated.")
        return
    with engine.begin() as conn:
        upsert_patterns(conn, pattern_df, batch_size)


def main():
    """
    CLI entry point.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--min-months", type=int, default=4)
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

    # One transaction for all batches, so an interrupted run leaves the old patterns intact.
    with engine.begin() as conn:
        for start in range(0, len(routes), args.route_batch):
            batch = routes[start:start + args.route_batch]
            hourly = load_hourly_monthly(engine, batch)
            pattern_df = solve_patterns(month_counts, hourly, args.min_months)
            upsert_patterns(conn, pattern_df, args.batch_size)

            total_input += len(hourly)
            total_output += len(pattern_df)
            done = min(start + args.route_batch, len(routes))
            print(f"routes {done}/{len(routes)}: monthly hourly rows {total_input:,}; "
                  f"stop pattern rows {total_output:,}")

    print(f"Done. Generated stop pattern rows: {total_output:,}")


if __name__ == "__main__":
    main()
