"""
Build lightweight subway hourly station demand from CardSubwayTime monthly CSV files.

Supported source formats:
1. API/XML-style exported columns:
   USE_MM, SBWY_ROUT_LN_NM, STTN,
   HR_0_GET_ON_NOPE, HR_0_GET_OFF_NOPE, ...

2. Seoul Open Data CSV-style Korean columns:
   사용월, 호선명, 지하철역,
   04시-05시 승차인원, 04시-05시 하차인원, ...

Parsing, column normalization, unpivoting, aggregation, and persistence are separated.
"""

from __future__ import annotations

import argparse
import calendar
from pathlib import Path

import numpy as np
import pandas as pd
from sqlalchemy import text

from common import load_holidays, make_engine, read_csv_flexible


DAY_TYPES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun_holiday"]

DOW_TO_DAY_TYPE = {
    0: "mon",
    1: "tue",
    2: "wed",
    3: "thu",
    4: "fri",
    5: "sat",
    6: "sun_holiday",
}


def parse_use_mm(value) -> tuple[int, int]:
    """
    Parse USE_MM into year and month.
    """
    use_mm = str(value).strip()
    if len(use_mm) != 6 or not use_mm.isdigit():
        raise ValueError(f"USE_MM must be YYYYMM: {value}")
    return int(use_mm[:4]), int(use_mm[4:])


def build_month_day_counts(use_mm: str, holidays: set[str]) -> dict[str, int]:
    """
    Count day-types in a month.
    """
    year, month = parse_use_mm(use_mm)
    counts = {day_type: 0 for day_type in DAY_TYPES}

    last_day = calendar.monthrange(year, month)[1]
    for day in range(1, last_day + 1):
        date = pd.Timestamp(year=year, month=month, day=day)
        date_str = date.strftime("%Y-%m-%d")

        if date_str in holidays:
            counts["sun_holiday"] += 1
        else:
            counts[DOW_TO_DAY_TYPE[date.weekday()]] += 1

    return counts


def get_numeric(row: pd.Series, column_name: str) -> float:
    """
    Safely read numeric values from a row.
    """
    if column_name not in row.index:
        return 0.0

    value = pd.to_numeric(row[column_name], errors="coerce")
    if pd.isna(value):
        return 0.0

    return float(value)


def detect_column(df: pd.DataFrame, candidates: list[str], logical_name: str) -> str:
    """
    Detect a required column from candidate names.

    CSV/API naming differences are handled here instead of scattered if-statements.
    """
    for candidate in candidates:
        if candidate in df.columns:
            return candidate

    raise ValueError(
        f"Required column not found for {logical_name}. "
        f"candidates={candidates}, columns={list(df.columns)}"
    )


def api_hour_column(hour: int, kind: str) -> str:
    """
    Build API-style hourly column names.
    """
    suffix = "GET_ON_NOPE" if kind == "boarding" else "GET_OFF_NOPE"
    return f"HR_{hour}_{suffix}"


def csv_hour_column(hour: int, kind: str) -> str:
    """
    Build Seoul CSV-style hourly column names.

    Example:
    hour=4, boarding -> 04시-05시 승차인원
    hour=0, alighting -> 00시-01시 하차인원
    """
    next_hour = (hour + 1) % 24
    label = "승차인원" if kind == "boarding" else "하차인원"
    return f"{hour:02d}시-{next_hour:02d}시 {label}"


def parse_card_subway_time_csv(path: Path) -> pd.DataFrame:
    """
    Parse one CardSubwayTime CSV into monthly long-format rows.

    Output grain:
    USE_MM × line × station × hour
    """
    df = read_csv_flexible(path)

    use_mm_col = detect_column(df, ["USE_MM", "사용월"], "use month")
    line_col = detect_column(df, ["SBWY_ROUT_LN_NM", "호선명"], "line name")
    station_col = detect_column(df, ["STTN", "지하철역", "역명"], "station name")

    records = []

    for _, row in df.iterrows():
        use_mm = str(row[use_mm_col]).strip()
        line_name = str(row[line_col]).strip()
        station_name = str(row[station_col]).strip()

        for hour in range(24):
            api_boarding_col = api_hour_column(hour, "boarding")
            api_alighting_col = api_hour_column(hour, "alighting")
            csv_boarding_col = csv_hour_column(hour, "boarding")
            csv_alighting_col = csv_hour_column(hour, "alighting")

            boarding = (
                get_numeric(row, api_boarding_col)
                if api_boarding_col in row.index
                else get_numeric(row, csv_boarding_col)
            )
            alighting = (
                get_numeric(row, api_alighting_col)
                if api_alighting_col in row.index
                else get_numeric(row, csv_alighting_col)
            )

            records.append({
                "use_mm": use_mm,
                "line_name": line_name,
                "station_name": station_name,
                "hour": hour,
                "monthly_boarding": boarding,
                "monthly_alighting": alighting,
            })

    return pd.DataFrame(records)


def solve_day_type_average(monthly_df: pd.DataFrame, holidays: set[str], min_months: int) -> pd.DataFrame:
    """
    Decompose monthly hourly counts into day-type average hourly demand.
    """
    if monthly_df.empty:
        return pd.DataFrame()

    month_counts = []
    for use_mm in sorted(monthly_df["use_mm"].unique()):
        counts = build_month_day_counts(use_mm, holidays)
        month_counts.append({"use_mm": use_mm, **counts})

    month_count_df = pd.DataFrame(month_counts)
    merged = monthly_df.merge(month_count_df, on="use_mm", how="inner")

    results = []

    for (line_name, station_name, hour), group in merged.groupby(["line_name", "station_name", "hour"]):
        if group["use_mm"].nunique() < min_months:
            continue

        A = group[DAY_TYPES].astype(float).to_numpy()
        boarding_b = group["monthly_boarding"].astype(float).to_numpy()
        alighting_b = group["monthly_alighting"].astype(float).to_numpy()

        boarding_x, *_ = np.linalg.lstsq(A, boarding_b, rcond=None)
        alighting_x, *_ = np.linalg.lstsq(A, alighting_b, rcond=None)

        boarding_x = np.clip(boarding_x, 0, None)
        alighting_x = np.clip(alighting_x, 0, None)

        for day_type, boarding_avg, alighting_avg in zip(DAY_TYPES, boarding_x, alighting_x):
            results.append({
                "line_name": line_name,
                "station_name": station_name,
                "day_type": day_type,
                "hour": int(hour),
                "boarding": float(boarding_avg),
                "alighting": float(alighting_avg),
            })

    if not results:
        return pd.DataFrame()

    demand = pd.DataFrame(results)

    return (
        demand
        .groupby(["line_name", "station_name", "day_type", "hour"], as_index=False)
        .agg(
            boarding=("boarding", "sum"),
            alighting=("alighting", "sum"),
        )
    )


def load_monthly_card_subway_files(input_dir: Path) -> pd.DataFrame:
    """
    Load and concatenate all CardSubwayTime CSV files in a directory.
    """
    csv_files = sorted(input_dir.glob("*.csv"))
    if not csv_files:
        raise FileNotFoundError(f"No CSV files found in: {input_dir}")

    parsed_frames = []

    for csv_file in csv_files:
        print(f"Parsing {csv_file.name}")
        parsed = parse_card_subway_time_csv(csv_file)
        print(f"Parsed monthly rows: {len(parsed)}")
        parsed_frames.append(parsed)

    combined = pd.concat(parsed_frames, ignore_index=True)

    return (
        combined
        .groupby(["use_mm", "line_name", "station_name", "hour"], as_index=False)
        .agg(
            monthly_boarding=("monthly_boarding", "sum"),
            monthly_alighting=("monthly_alighting", "sum"),
        )
    )


def save_demand(engine, demand: pd.DataFrame, batch_size: int) -> None:
    """
    Save lightweight subway demand using batch upsert.
    """
    if demand.empty:
        print("No subway demand rows generated.")
        return

    demand = demand.copy()
    demand["source"] = "card_subway_time_monthly_lstsq_light"

    sql = text("""
        INSERT INTO subway_hourly_station_demand_light (
            line_name,
            station_name,
            day_type,
            hour,
            boarding,
            alighting,
            source
        )
        VALUES (
            :line_name,
            :station_name,
            :day_type,
            :hour,
            :boarding,
            :alighting,
            :source
        )
        ON CONFLICT (line_name, station_name, day_type, hour)
        DO UPDATE SET
            boarding = EXCLUDED.boarding,
            alighting = EXCLUDED.alighting,
            source = EXCLUDED.source
    """)

    records = demand.to_dict(orient="records")
    total = len(records)

    with engine.begin() as conn:
        for start in range(0, total, batch_size):
            batch = records[start:start + batch_size]
            conn.execute(sql, batch)
            print(f"{min(start + batch_size, total)}/{total} lightweight subway rows saved")


def main():
    """
    CLI entry point.
    """
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--input-dir", required=True)
    parser.add_argument("--holiday-csv", required=False)
    parser.add_argument("--min-months", type=int, default=4)
    parser.add_argument("--batch-size", type=int, default=50000)
    args = parser.parse_args()

    input_dir = Path(args.input_dir)
    if not input_dir.exists():
        raise FileNotFoundError(f"input directory not found: {input_dir}")

    holidays = load_holidays(args.holiday_csv)
    engine = make_engine(args.db_url)

    monthly = load_monthly_card_subway_files(input_dir)
    print(f"Combined monthly subway rows: {len(monthly)}")

    demand = solve_day_type_average(monthly, holidays, args.min_months)
    print(f"Generated lightweight subway demand rows: {len(demand)}")

    save_demand(engine, demand, args.batch_size)

    print("Done building lightweight subway station demand from CardSubwayTime.")


if __name__ == "__main__":
    main()
