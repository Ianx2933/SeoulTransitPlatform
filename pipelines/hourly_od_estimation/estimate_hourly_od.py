"""
Estimate hourly OD records from daily OD and day-type hourly ratios.
"""

from __future__ import annotations

import argparse
import os
import numpy as np
import pandas as pd
from sqlalchemy import text

from .common import (
    DEFAULT_HOLIDAY_CSV,
    copy_frame,
    day_type_from_yyyymmdd,
    load_holidays,
    make_engine,
    normalize_ars,
    prepare_next_table,
    require_holiday_coverage,
    table_target,
    target_table,
)


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


# Typical days (2026-11). od_typical_route_stop holds the mean OD of a day type
# over the last twelve months, built in BigQuery from every corrected TOPIS OD
# day. Its 기준일자 is a label, so the hourly table keys a typical day the way it
# keyed a date. The ratio model has seven day types, so a typical weekday takes
# the mean of the fitted Monday-to-Friday profiles of its route and stop.
TYPICAL_PREFIX = "typical_"
TYPICAL_DAY_TYPES = ("saturday", "sunday_holiday", "weekday")   # label order
TYPICAL_RATIO_DAY_TYPE = {"weekday": "weekday", "saturday": "sat", "sunday_holiday": "sun_holiday"}
WEEKDAY_RATIO_DAY_TYPES = ("mon", "tue", "wed", "thu", "fri")
TYPICAL_SOURCE_TABLE = "od_typical_route_stop"


def typical_labels(day_types: list[str]) -> tuple[str, str]:
    """
    (first, last) label for a set of typical day types, used as a 기준일자 range.

    The range must cover exactly the chosen labels, so the set has to be
    contiguous in label order (saturday < sunday_holiday < weekday): weekday,
    weekday+sunday_holiday or all three, but not saturday+weekday alone.
    """
    unknown = [d for d in day_types if d not in TYPICAL_DAY_TYPES]
    chosen = sorted(set(day_types) - set(unknown), key=TYPICAL_DAY_TYPES.index)
    if unknown or not chosen:
        raise SystemExit(f"--day-types must be some of {', '.join(TYPICAL_DAY_TYPES)}, got {day_types}")
    first, last = TYPICAL_DAY_TYPES.index(chosen[0]), TYPICAL_DAY_TYPES.index(chosen[-1])
    if list(TYPICAL_DAY_TYPES[first:last + 1]) != chosen:
        raise SystemExit(f"--day-types {chosen} leave a gap in label order {list(TYPICAL_DAY_TYPES)}; "
                         "add the day type in between")
    return TYPICAL_PREFIX + chosen[0], TYPICAL_PREFIX + chosen[-1]


def ratio_day_type(label_or_date: str, holidays: set[str]) -> str:
    """Ratio-model day type for a YYYYMMDD date or a typical_<day type> label."""
    if label_or_date.startswith(TYPICAL_PREFIX):
        return TYPICAL_RATIO_DAY_TYPE[label_or_date[len(TYPICAL_PREFIX):]]
    return day_type_from_yyyymmdd(label_or_date, holidays)


def add_weekday_mean(ratio_map: dict, zero_keys: set) -> int:
    """
    Add (route, stop, 'weekday') profiles: the mean of that key's fitted
    Monday-to-Friday profiles. Each sums to one, so the mean does too. A key with
    no fitted weekday profile but a collapsed one goes to zero_keys, keeping the
    fallback methods distinct as select_profile documents. Returns profiles added.
    """
    groups: dict[tuple[str, str], list[list[float]]] = {}
    collapsed: set[tuple[str, str]] = set()
    for (route_no, stop_ars, day_type), ratios in ratio_map.items():
        if day_type in WEEKDAY_RATIO_DAY_TYPES:
            groups.setdefault((route_no, stop_ars), []).append(ratios)
    for route_no, stop_ars, day_type in zero_keys:
        if day_type in WEEKDAY_RATIO_DAY_TYPES:
            collapsed.add((route_no, stop_ars))
    for (route_no, stop_ars), profiles in groups.items():
        ratio_map[(route_no, stop_ars, "weekday")] = np.mean(np.array(profiles), axis=0).tolist()
    for route_no, stop_ars in collapsed - set(groups):
        zero_keys.add((route_no, stop_ars, "weekday"))
    return len(groups)


LABEL_WIDTH = 32


def widen_label_column(conn) -> bool:
    """
    estimated_hourly_od.기준일자 was VARCHAR(8), sized for YYYYMMDD; a label such
    as typical_sunday_holiday needs 22. Widening a VARCHAR does not rewrite the
    table or its key index in PostgreSQL, so it is quick. Does nothing when the
    column is already wide enough. Returns True when it changed the column.
    """
    width = conn.execute(text("""
        SELECT character_maximum_length FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = :t AND column_name = '기준일자'
    """), {"t": OD_TABLE}).scalar()
    if width is None or width >= LABEL_WIDTH:
        return False
    conn.exec_driver_sql(f"ALTER TABLE {OD_TABLE} ALTER COLUMN 기준일자 TYPE VARCHAR({LABEL_WIDTH})")
    return True


def load_typical_od(engine, first_label: str, last_label: str, limit: int | None) -> pd.DataFrame:
    """
    Load typical-day OD in the shape load_daily_od returns. Stops without an ARS
    (keyed 'name:<stop>', the Han River ferry and a few virtual stops) are left
    out like '00000' is: the ratio model has no stop-level profile for them.
    """
    limit_clause = f" LIMIT {int(limit)}" if limit else ""
    sql = text(f"""
        SELECT
            기준일자,
            노선명,
            승차_정류장ars,
            하차_정류장ars,
            SUM(승객수) AS daily_passengers
        FROM {TYPICAL_SOURCE_TABLE}
        WHERE 승차_정류장ars != '00000' AND 하차_정류장ars != '00000'
          AND 승차_정류장ars NOT LIKE 'name:%%' AND 하차_정류장ars NOT LIKE 'name:%%'
          AND 기준일자 >= :start_date AND 기준일자 <= :end_date
        GROUP BY 기준일자, 노선명, 승차_정류장ars, 하차_정류장ars
        {limit_clause}
    """)
    return pd.read_sql(sql, engine, params={"start_date": first_label, "end_date": last_label})


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


def load_ratio_map(engine, day_types: list[str] | None = None, table: str = "dow_hourly_ratio"):
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
        FROM {table}
        {day_filter}
    """), engine, params=params)

    return build_ratio_map(df)


OD_TABLE = "estimated_hourly_od"
OD_COLUMNS = ["기준일자", "노선명", "승차_정류장ars", "하차_정류장ars",
              "hour", "estimated_passengers", "method"]


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
             batch_size: int, conn=None, writer=None) -> int:
    """
    Apply hourly ratios to daily OD records.

    The origin stop's boarding ratio is used because the OD starts at the boarding stop.
    This is a transparent baseline assumption that can later be replaced by LSTM
    or more detailed trip-chain inference.

    Every input row produces exactly 24 output rows whose estimates sum to the
    daily passenger count, whichever profile was selected.

    writer, when given, receives each batch of records instead of the upsert.
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
            day_type = ratio_day_type(date, holidays)
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
            if writer is not None:
                writer(buffer)
            else:
                save_estimates(engine, buffer, conn)
            total += len(buffer)
            buffer.clear()
            if total >= next_report:
                print(f"Inserted/updated {total:,} hourly OD rows")
                next_report += 500_000

    if buffer:
        if writer is not None:
            writer(buffer)
        else:
            save_estimates(engine, buffer, conn)
        total += len(buffer)

    return total


def main():
    # CLI entry point for hourly OD estimation.
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--db-url",
        default=os.environ.get("PIPELINE_DB_URL"),
        required="PIPELINE_DB_URL" not in os.environ,
        help="SQLAlchemy URL. Defaults to PIPELINE_DB_URL, so a scheduler can pass "
             "it from a secret instead of a visible argument.",
    )
    parser.add_argument("--source", choices=["dated", "typical"], default="dated",
                        help="dated: days of analysis_table_final between --start-date and "
                             "--end-date. typical: the typical days of od_typical_route_stop "
                             "named by --day-types; the hourly table then holds typical days only.")
    parser.add_argument("--day-types", default="weekday",
                        help="With --source typical: comma list of " + ", ".join(TYPICAL_DAY_TYPES))
    parser.add_argument("--start-date", required=False, help="YYYYMMDD")
    parser.add_argument("--end-date", required=False, help="YYYYMMDD")
    parser.add_argument("--holiday-csv", default=str(DEFAULT_HOLIDAY_CSV))
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--batch-size", type=int, default=5000)
    parser.add_argument("--target", choices=["live", "next"], default=None,
                        help="Write estimated_hourly_od itself (live) or a copy for "
                             "publish_rebuild (next), reading ratios from the same target. "
                             "Defaults to PIPELINE_TABLE_TARGET, else live.")
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Delete existing estimates in the date range first, in the same transaction. "
             "Needed after ratios change, because method is part of the key and a row "
             "that switches method would otherwise be kept twice.",
    )
    args = parser.parse_args()

    target = table_target(args.target)
    typical = args.source == "typical"
    if typical:
        if args.start_date or args.end_date:
            raise SystemExit("--source typical takes --day-types, not dates.")
        args.start_date, args.end_date = typical_labels(
            [d.strip() for d in args.day_types.split(",") if d.strip()])
    if target == "next" and not (args.start_date and args.end_date):
        raise SystemExit("Writing the next copy needs both --start-date and --end-date.")
    if target == "next" and args.limit:
        raise SystemExit("Writing the next copy cannot be combined with --limit.")
    if args.replace and not (args.start_date and args.end_date):
        raise SystemExit("--replace needs both --start-date and --end-date.")
    if args.replace and args.limit:
        raise SystemExit("--replace cannot be combined with --limit.")

    engine = make_engine(args.db_url)
    holidays = load_holidays(args.holiday_csv)
    if typical:
        with engine.begin() as conn:
            if widen_label_column(conn):
                print(f"Widened {OD_TABLE}.기준일자 to VARCHAR({LABEL_WIDTH}) for typical-day labels")

    if typical:
        daily_od = load_typical_od(engine, args.start_date, args.end_date, args.limit)
        if daily_od.empty:
            raise SystemExit(f"{TYPICAL_SOURCE_TABLE} has no rows {args.start_date}..{args.end_date}")
        day_types = sorted({ratio_day_type(str(d), holidays) for d in daily_od["기준일자"].unique()})
        load_types = sorted({t for t in day_types if t != "weekday"} |
                            (set(WEEKDAY_RATIO_DAY_TYPES) if "weekday" in day_types else set()))
    else:
        daily_od = load_daily_od(engine, args.start_date, args.end_date, args.limit)
        require_holiday_coverage(holidays, {str(d)[:4] for d in daily_od["기준일자"].unique()})
        day_types = sorted({day_type_from_yyyymmdd(str(d), holidays) for d in daily_od["기준일자"].unique()})
        load_types = day_types
    ratio_table = target_table("dow_hourly_ratio", target)
    ratio_map, zero_keys = (load_ratio_map(engine, load_types, ratio_table) if load_types
                            else ({}, set()))
    if typical and "weekday" in day_types:
        print(f"Weekday profiles averaged from Monday-Friday: {add_weekday_mean(ratio_map, zero_keys):,}")

    print(f"Daily OD rows: {len(daily_od):,}")
    print(f"Day types: {day_types}")
    print(f"Ratio keys usable: {len(ratio_map):,}")
    print(f"Ratio keys all zero, routed to fallback: {len(zero_keys):,}")
    print(f"Ratios from {ratio_table}")

    # Rows this run does not replace survive it: other dates for a dated run, other
    # typical days for a typical run. A typical run drops every dated row, so the
    # hourly table never mixes one real day with typical ones.
    keep_where = "NOT (기준일자 >= :start_date AND 기준일자 <= :end_date)"
    if typical:
        keep_where = f"LEFT(기준일자, {len(TYPICAL_PREFIX)}) = '{TYPICAL_PREFIX}' AND " + keep_where

    if target == "next":
        with engine.begin() as conn:
            nxt = prepare_next_table(
                conn, OD_TABLE, keep_where=keep_where,
                params={"start_date": args.start_date, "end_date": args.end_date})
            kept = conn.execute(text(f"SELECT COUNT(*) FROM {nxt}")).scalar()
            print(f"{nxt}: kept {kept:,} rows outside {args.start_date}-{args.end_date}")
            total = estimate(engine, daily_od, ratio_map, zero_keys, holidays, args.batch_size,
                             writer=lambda records: copy_frame(
                                 conn, pd.DataFrame(records, columns=OD_COLUMNS), nxt, OD_COLUMNS))
        with engine.begin() as conn:
            conn.execute(text(f"ANALYZE {nxt}"))
        print(f"Done. Inserted {nxt} rows: {total:,}")
        return

    if not args.replace:
        total = estimate(engine, daily_od, ratio_map, zero_keys, holidays, args.batch_size)
        print(f"Done. Inserted/updated estimated_hourly_od rows: {total:,}")
        return

    with engine.begin() as conn:
        deleted = conn.execute(
            text(f"DELETE FROM estimated_hourly_od WHERE NOT ({keep_where})"),
            {"start_date": args.start_date, "end_date": args.end_date},
        ).rowcount
        print(f"Deleted existing rows in range: {deleted:,}")
        total = estimate(engine, daily_od, ratio_map, zero_keys, holidays, args.batch_size, conn=conn)
    print(f"Done. Inserted estimated_hourly_od rows: {total:,}")


if __name__ == "__main__":
    main()
