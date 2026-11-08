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

The monthly table is read once, as one query ordered by route and streamed
through a server-side cursor, and cut into the same route batches the previous
version queried one at a time. Each per-batch query had to find its routes'
rows scattered across every month of a 30-million-row table; on Cloud SQL that
took about seven minutes per 25 routes and 3 h 15 min for the whole run
(2026-11-07). Results are written with COPY into a temporary table and one
INSERT ... ON CONFLICT, instead of one multi-row VALUES statement per batch of
records. Batches, solutions and stored values are the same as before.
"""

from __future__ import annotations

import argparse
import io
import os
import time
from typing import Iterable, Iterator

import numpy as np
import pandas as pd
from sqlalchemy import text
from sqlalchemy.exc import OperationalError

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


HOURLY_COLUMNS = ["use_ym", "route_no", "stop_ars", "hour", "monthly_boarding"]


def stream_hourly_monthly(engine, start_route: str | None = None, chunk_rows: int = 200_000,
                          work_mem: str | None = None) -> Iterator[pd.DataFrame]:
    """
    Read the same rows as load_hourly_monthly for every route, in one query
    ordered by route, and yield them in chunks as they arrive.

    The ordering is what lets the caller cut route batches without a query per
    batch. start_route skips routes sorting before it, to resume after a
    dropped connection. work_mem applies to this query only, so the sort stays
    in memory instead of spilling to disk.
    """
    start_filter = "AND route_no >= :start_route" if start_route is not None else ""
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
          {start_filter}
        GROUP BY use_ym, route_no, stop_ars, hour
        ORDER BY route_no
    """)
    params = {"start_route": start_route} if start_route is not None else {}
    with engine.connect() as conn:
        conn = conn.execution_options(stream_results=True, max_row_buffer=chunk_rows)
        if work_mem:
            conn.execute(text("SELECT set_config('work_mem', :value, true)"), {"value": work_mem})
        yield from pd.read_sql(sql, conn, params=params, chunksize=chunk_rows)


def route_batches(chunks: Iterable[pd.DataFrame], routes: list[str], route_batch: int,
                  start_index: int = 0, columns: list[str] = HOURLY_COLUMNS
                  ) -> Iterator[tuple[int, pd.DataFrame]]:
    """
    Regroup route-ordered chunks into the batches routes[s:s + route_batch]
    for s = start_index, start_index + route_batch, ...

    Every batch is yielded, in order, including batches whose routes have no
    rows, so progress and resume points match the per-batch version. Rows must
    arrive ordered by their route's position in ``routes``; a row out of order
    or from an unknown route raises instead of being put in the wrong batch.
    """
    position = pd.Series(np.arange(len(routes)), index=pd.Index(routes, dtype=object))
    starts = list(range(start_index, len(routes), route_batch))
    current, pending, last = 0, [], start_index

    def take():
        frame = (pd.concat(pending, ignore_index=True) if pending
                 else pd.DataFrame(columns=columns))
        pending.clear()
        return starts[current], frame

    for chunk in chunks:
        if chunk.empty:
            continue
        pos = chunk["route_no"].map(position)
        if pos.isna().any():
            unknown = chunk.loc[pos.isna(), "route_no"].iloc[0]
            raise ValueError(f"route {unknown!r} is not in the route list")
        pos = pos.to_numpy(dtype=np.int64)
        if pos[0] < last or (np.diff(pos) < 0).any():
            raise ValueError("rows are not ordered by route; batches would mix routes")
        last = int(pos[-1])
        batch_of = (pos - start_index) // route_batch
        for b in np.unique(batch_of):
            while current < b:
                yield take()
                current += 1
            pending.append(chunk.loc[batch_of == b])

    while current < len(starts):
        yield take()
        current += 1


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


RATIO_TABLE = "dow_hourly_ratio"
RATIO_COLUMNS = ["route_no", "stop_ars", "day_type", "hour",
                 "avg_boarding_passengers", "boarding_ratio", "method"]
RATIO_KEY = ["route_no", "stop_ars", "day_type", "hour"]


def copy_upsert(conn, frame: pd.DataFrame, table: str, columns: list[str],
                key: list[str]) -> None:
    """
    Upsert ``frame`` through COPY into a temporary table and one INSERT.

    Gives the same rows as UPSERT_SQL. Floats are written by pandas with the
    shortest representation that reads back to the same double, so stored
    values do not change. A key repeated within one batch fails here, where
    row-by-row upserts would have kept the last one silently.
    """
    if frame.empty:
        return
    stage = f"_stage_{table}"
    cols = ", ".join(columns)
    updates = ", ".join(f"{c} = EXCLUDED.{c}" for c in columns if c not in key)
    buffer = io.StringIO()
    frame[columns].to_csv(buffer, index=False, header=False)
    buffer.seek(0)

    conn.exec_driver_sql(
        f"CREATE TEMP TABLE {stage} ON COMMIT DROP AS SELECT {cols} FROM {table} WITH NO DATA"
    )
    cursor = conn.connection.cursor()
    try:
        cursor.copy_expert(f"COPY {stage} ({cols}) FROM STDIN WITH (FORMAT csv)", buffer)
    finally:
        cursor.close()
    conn.exec_driver_sql(
        f"INSERT INTO {table} ({cols}) SELECT {cols} FROM {stage} "
        f"ON CONFLICT ({', '.join(key)}) DO UPDATE SET {updates}"
    )


def write_batch(engine, ratio_df: pd.DataFrame) -> None:
    """Write one batch in its own transaction."""
    with engine.begin() as conn:
        copy_upsert(conn, ratio_df, RATIO_TABLE, RATIO_COLUMNS, RATIO_KEY)


RETRY_ATTEMPTS = 3
RETRY_WAIT_SECONDS = 30


def with_retry(engine, work, label, attempts=RETRY_ATTEMPTS, wait=RETRY_WAIT_SECONDS,
               sleep=time.sleep):
    """
    Run ``work()`` and retry it when the database connection drops.

    On Cloud Run the connection to Cloud SQL can drop mid-query while the
    database itself keeps running (seen on 2026-11-06: both the reading and the
    writing connection closed at the same instant, and the server noticed six
    minutes later). The pool is discarded before each retry so the next attempt
    opens fresh connections. Each batch is an upsert, so repeating it gives the
    same rows.
    """
    for attempt in range(1, attempts + 1):
        try:
            return work()
        except OperationalError as error:
            if attempt == attempts:
                raise
            first_line = str(error).strip().splitlines()[0]
            print(f"  {label}: connection lost ({first_line}); "
                  f"retry {attempt}/{attempts - 1} in {wait}s")
            engine.dispose()
            sleep(wait)


def run(engine, routes: list[str], month_counts: pd.DataFrame, route_batch: int,
        min_months: int, start_index: int = 0, chunk_rows: int = 200_000,
        work_mem: str | None = None, stream=stream_hourly_monthly, write=write_batch,
        attempts: int = RETRY_ATTEMPTS, wait: int = RETRY_WAIT_SECONDS,
        sleep=time.sleep, clock=time.perf_counter) -> tuple[int, int]:
    """
    Read, solve and write every batch from start_index on.

    A dropped connection restarts the read at the first batch not yet written;
    batches already committed are not redone. The run fails after ``attempts``
    drops in a row without a batch completing in between.
    """
    next_index, failures = start_index, 0
    total_input = total_output = 0

    while next_index < len(routes):
        chunks = stream(engine, routes[next_index] if next_index > 0 else None,
                        chunk_rows, work_mem)
        try:
            mark = clock()
            for start, hourly in route_batches(chunks, routes, route_batch, next_index):
                read_s = clock() - mark
                ratio_df = solve_patterns(month_counts, hourly, min_months)
                solved = clock()
                write(engine, ratio_df)
                written = clock()

                next_index = min(start + route_batch, len(routes))
                failures = 0
                total_input += len(hourly)
                total_output += len(ratio_df)
                print(f"routes {next_index}/{len(routes)}: monthly hourly rows {total_input:,}; "
                      f"ratio rows {total_output:,} "
                      f"(read {read_s:.1f}s, solve {solved - mark - read_s:.1f}s, "
                      f"write {written - solved:.1f}s)")
                mark = clock()
        except OperationalError as error:
            failures += 1
            if failures >= attempts:
                raise
            first_line = str(error).strip().splitlines()[0]
            print(f"  routes {next_index + 1}-: connection lost ({first_line}); "
                  f"retry {failures}/{attempts - 1} in {wait}s from route {next_index + 1}")
            engine.dispose()
            sleep(wait)
        finally:
            close = getattr(chunks, "close", None)
            if close:
                close()

    return total_input, total_output


def main():
    """
    CLI entry point for least-squares ratio estimation.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--db-url",
        default=os.environ.get("PIPELINE_DB_URL"),
        required="PIPELINE_DB_URL" not in os.environ,
        help="SQLAlchemy URL. Defaults to PIPELINE_DB_URL, so a scheduler can pass "
             "it from a secret instead of a visible argument.",
    )
    parser.add_argument("--min-months", type=int, default=3)
    parser.add_argument("--batch-size", type=int, default=50000,
                        help="Unused since writes go through COPY. Accepted so existing "
                             "job arguments keep working.")
    parser.add_argument("--route-batch", type=int, default=50,
                        help="Routes solved and committed per batch. Lower it if memory is tight.")
    parser.add_argument("--start-route-index", type=int, default=0,
                        help="Skip the first N routes of the sorted list, to resume a run "
                             "that stopped part way. Batches already written stay as they are.")
    parser.add_argument("--chunk-rows", type=int, default=200_000,
                        help="Rows fetched from the server-side cursor at a time.")
    parser.add_argument("--work-mem", default="256MB",
                        help="work_mem for the read query, so its sort stays in memory. "
                             "Empty to use the server default.")
    args = parser.parse_args()

    engine = make_engine(args.db_url)
    month_counts = load_month_counts(engine)
    routes = load_route_list(engine)

    print(f"Loaded month count rows: {len(month_counts)}")
    print(f"Routes: {len(routes)} (batch {args.route_batch})")

    # Each batch commits on its own. A single transaction over all batches kept
    # the old values intact if a run stopped, but one dropped connection after
    # hours of work threw everything away, and the open transaction held WAL
    # until the end. A run that stops part way now leaves some routes with new
    # values; the next steps do not run after a failed step, and the next run
    # rewrites every route, so mixed values are never published.
    _, total_output = run(
        engine, routes, month_counts, args.route_batch, args.min_months,
        start_index=args.start_route_index, chunk_rows=args.chunk_rows,
        work_mem=args.work_mem or None,
    )

    # The OD estimator reads this table next; fresh statistics keep its plan
    # from being chosen for the table as it was before the rewrite.
    with engine.begin() as conn:
        conn.execute(text(f"ANALYZE {RATIO_TABLE}"))

    print(f"Done. Generated ratio rows: {total_output:,}")


if __name__ == "__main__":
    main()
