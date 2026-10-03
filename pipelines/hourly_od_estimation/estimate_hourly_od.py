"""
Estimate hourly OD records from daily OD and day-type hourly ratios.
"""

from __future__ import annotations

import argparse
import numpy as np
import pandas as pd
from sqlalchemy import text

from .common import day_type_from_yyyymmdd, load_holidays, make_engine, normalize_ars


# Fallback ratio used when a route-stop-day_type profile cannot be applied.
# The hand-written profile summed to 1.105, which inflated fallback estimates
# by 10.5%. It is normalized so the 24 values distribute exactly the daily total.
_RAW_DEFAULT_RATIO = [
    0.01, 0.005, 0.003, 0.002, 0.01, 0.04,
    0.08, 0.11, 0.09, 0.05, 0.04, 0.04,
    0.05, 0.05, 0.05, 0.06, 0.08, 0.10,
    0.09, 0.06, 0.04, 0.025, 0.015, 0.005
]
DEFAULT_RATIO = [value / sum(_RAW_DEFAULT_RATIO) for value in _RAW_DEFAULT_RATIO]

# Three mutually exclusive provenance values, recorded per row.
# `method` is part of the estimated_hourly_od key, so a row that changes
# method must be written through --replace rather than upserted alongside.
METHOD_FITTED = "dow_least_squares_sun_holiday"
METHOD_FALLBACK_NO_KEY = "default_static_ratio"
METHOD_FALLBACK_ZERO_FIT = "default_static_ratio_zero_fit"


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


def build_ratio_map(df: pd.DataFrame) -> tuple[dict[tuple[str, str, str], list[float]], set[tuple[str, str, str]]]:
    """
    Convert ratio rows into a lookup map, separating profiles that cannot be applied.

    Pure function: no database access, so the precedence rules below are testable
    against synthetic frames.

    A key whose 24 ratios sum to zero is not a usable profile. It is produced when
    every hourly least-squares estimate for that route-stop-day_type was negative
    and clipped to zero, and multiplying daily passengers by it would silently drop
    them. Those keys are returned separately so the caller can route them to the
    fallback profile and record a distinct method.

    Returns (ratio_map, zero_keys).
    """
    if df.empty:
        return {}, set()
    df = df.reset_index(drop=True).copy()
    required = ["route_no", "stop_ars", "day_type", "hour", "boarding_ratio"]
    if df[required].isna().any().any():
        raise ValueError("ratio rows contain NULL keys, hours or values")
    df["route_no"] = df["route_no"].astype(str).str.strip()
    df["stop_ars"] = df["stop_ars"].map(normalize_ars)
    df["day_type"] = df["day_type"].astype(str).str.strip()
    hours_numeric = pd.to_numeric(df["hour"], errors="raise").to_numpy(dtype=float)
    ratios = pd.to_numeric(df["boarding_ratio"], errors="raise").to_numpy(dtype=float)
    if not np.isfinite(hours_numeric).all() or not np.isfinite(ratios).all():
        raise ValueError("ratio hours and values must be finite")
    if ((hours_numeric < 0) | (hours_numeric > 23) |
            (hours_numeric != np.floor(hours_numeric))).any():
        raise ValueError("ratio hours must be integers from 0 through 23")
    if (ratios < 0).any():
        raise ValueError("ratio values must be nonnegative")
    if df.duplicated(["route_no", "stop_ars", "day_type", "hour"]).any():
        raise ValueError("duplicate ratio key/hour after key normalization")
    grouped = df.groupby(["route_no", "stop_ars", "day_type"], sort=True)
    if (grouped.size() != 24).any():
        raise ValueError("every ratio profile must contain all 24 hours")
    gid = grouped.ngroup().to_numpy()
    hours = hours_numeric.astype(int)
    matrix = np.zeros((int(gid.max()) + 1, 24))
    matrix[gid, hours] = ratios

    # One sum per group, computed once here rather than per OD row at estimation time.
    row_sums = matrix.sum(axis=1)
    invalid = (row_sums != 0) & ~np.isclose(row_sums, 1.0, rtol=0, atol=1e-6)
    if invalid.any():
        raise ValueError("nonzero ratio profiles must sum to one within 1e-6")
    # Remove harmless normalization roundoff before passenger allocation.
    usable = row_sums > 0
    matrix[usable] /= row_sums[usable, None]

    _, first_row = np.unique(gid, return_index=True)
    keys = df.loc[first_row, ["route_no", "stop_ars", "day_type"]].to_numpy()

    ratio_map: dict[tuple[str, str, str], list[float]] = {}
    zero_keys: set[tuple[str, str, str]] = set()
    for index, (route_no, stop_ars, day_type) in enumerate(keys):
        key = (str(route_no).strip(), normalize_ars(stop_ars), str(day_type))
        if row_sums[index] <= 0:
            zero_keys.add(key)
        else:
            ratio_map[key] = matrix[index].tolist()
    return ratio_map, zero_keys


def load_ratio_map(engine, day_types: list[str] | None = None):
    """
    Load ratio table into an in-memory lookup map.

    The lookup key is (route_no, origin_ars, day_type).
    Only the day types needed for the requested dates are read, which keeps
    memory at roughly one seventh of the full table for a single date.

    Returns (ratio_map, zero_keys); see build_ratio_map.
    """
    day_filter = "WHERE day_type = ANY(:day_types)" if day_types else ""
    params = {"day_types": list(day_types)} if day_types else {}
    df = pd.read_sql(text(f"""
        SELECT route_no, stop_ars, day_type, hour, boarding_ratio
        FROM dow_hourly_ratio
        {day_filter}
    """), engine, params=params)

    return build_ratio_map(df)


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


def select_profile(key: tuple[str, str, str], ratio_map: dict, zero_keys: set) -> tuple[list[float], str]:
    """
    Choose the hourly profile for one OD row and record which rule produced it.
    (OD 한 행에 쓸 시간대 프로필을 고르고, 어떤 규칙이 적용됐는지 기록한다.)

    Precedence, strongest first:
      1. a fitted profile whose ratios sum to one
      2. the fallback profile, because the fit collapsed to all zeros
      3. the fallback profile, because no profile exists for this key at all

    Cases 2 and 3 use the same numbers but are different data conditions: the
    first means the route-stop was observed and the decomposition failed, the
    second means it was never fitted. Collapsing them into one method would
    hide which of the two is growing.
    """
    ratios = ratio_map.get(key)
    if ratios is not None:
        return ratios, METHOD_FITTED
    if key in zero_keys:
        return DEFAULT_RATIO, METHOD_FALLBACK_ZERO_FIT
    return DEFAULT_RATIO, METHOD_FALLBACK_NO_KEY


def estimate(engine, daily_od: pd.DataFrame, ratio_map: dict, zero_keys: set, holidays: set[str],
             batch_size: int, conn=None) -> int:
    """
    Apply hourly ratios to daily OD records.

    The origin stop's boarding ratio is used because the OD starts at the boarding stop.
    This is a transparent baseline assumption that can later be replaced by LSTM
    or more detailed trip-chain inference.

    Every input row produces exactly 24 output rows whose estimates sum to the
    daily passenger count, whichever profile was selected.
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

        ratios, method = select_profile((route_no, origin_ars, day_type), ratio_map, zero_keys)

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
    ratio_map, zero_keys = load_ratio_map(engine, day_types) if day_types else ({}, set())

    print(f"Daily OD rows: {len(daily_od):,}")
    print(f"Day types: {day_types}")
    print(f"Ratio keys usable: {len(ratio_map):,}")
    print(f"Ratio keys all zero, routed to fallback: {len(zero_keys):,}")

    if not args.replace:
        total = estimate(engine, daily_od, ratio_map, zero_keys, holidays, args.batch_size)
        print(f"Done. Inserted/updated estimated_hourly_od rows: {total:,}")
        return

    with engine.begin() as conn:
        deleted = conn.execute(
            text("DELETE FROM estimated_hourly_od WHERE 기준일자 >= :start_date AND 기준일자 <= :end_date"),
            {"start_date": args.start_date, "end_date": args.end_date},
        ).rowcount
        print(f"Deleted existing rows in range: {deleted:,}")
        total = estimate(engine, daily_od, ratio_map, zero_keys, holidays, args.batch_size, conn=conn)
    print(f"Done. Inserted estimated_hourly_od rows: {total:,}")


if __name__ == "__main__":
    main()
