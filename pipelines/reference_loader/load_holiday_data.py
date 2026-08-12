"""
Holiday Reference Loader

Purpose:
- Load a public holiday CSV into the holiday_config table.
- holiday_config drives isHoliday in prediction features.

Configuration comes from the environment so that no credential is stored in
the repository.

Required:
    PIPELINE_DB_URL   postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit

Usage:
    python pipelines/reference_loader/load_holiday_data.py \
        --csv "data/reference/holiday_list.csv"

Source CSV columns (cp949):
    날짜, 휴일명
"""

import argparse
import os
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine, text


TABLE_NAME = "holiday_config"
REQUIRED_COLUMNS = ["날짜", "휴일명"]


def read_holiday_csv(csv_path: Path) -> pd.DataFrame:
    # Read the holiday CSV and normalize its columns.

    if not csv_path.exists():
        raise FileNotFoundError(f"CSV file not found: {csv_path}")

    try:
        raw = pd.read_csv(csv_path, encoding="cp949")
    except UnicodeDecodeError:
        raw = pd.read_csv(csv_path, encoding="utf-8-sig")

    raw.columns = raw.columns.str.strip()

    missing = [column for column in REQUIRED_COLUMNS if column not in raw.columns]
    if missing:
        raise ValueError(
            f"Missing required columns: {missing}. Found: {list(raw.columns)}"
        )

    # 날짜 is stored as DATE, so parse rather than passing strings through.
    frame = pd.DataFrame(
        {
            "날짜": pd.to_datetime(raw["날짜"]).dt.date,
            "휴일명": raw["휴일명"].astype(str).str.strip(),
        }
    )

    return frame.drop_duplicates(subset="날짜")


def ensure_table(engine) -> None:
    """Create holiday_config if it does not exist.

    This mirrors database/schema/01_curated_od.sql. Keep both in sync.
    """

    with engine.begin() as connection:
        connection.execute(
            text(
                """
                CREATE TABLE IF NOT EXISTS public.holiday_config (
                    날짜    DATE PRIMARY KEY,
                    휴일명  VARCHAR(50)
                )
                """
            )
        )


def load_holidays(csv_path: Path, db_url: str, replace: bool) -> int:
    # Load holiday rows, upserting on 날짜.

    frame = read_holiday_csv(csv_path)
    engine = create_engine(db_url)

    ensure_table(engine)

    with engine.begin() as connection:
        if replace:
            connection.execute(text(f"TRUNCATE TABLE public.{TABLE_NAME}"))

        for row in frame.itertuples(index=False):
            connection.execute(
                text(
                    f"""
                    INSERT INTO public.{TABLE_NAME} (날짜, 휴일명)
                    VALUES (:날짜, :휴일명)
                    ON CONFLICT (날짜) DO UPDATE SET 휴일명 = EXCLUDED.휴일명
                    """
                ),
                {"날짜": row.날짜, "휴일명": row.휴일명},
            )

    return len(frame)


def main() -> None:
    parser = argparse.ArgumentParser(description="Load holiday reference data.")
    parser.add_argument("--csv", required=True, help="Path to the holiday CSV")
    parser.add_argument(
        "--replace",
        action="store_true",
        help="Truncate the table before insert",
    )
    args = parser.parse_args()

    db_url = os.environ.get("PIPELINE_DB_URL")
    if not db_url:
        raise SystemExit(
            "PIPELINE_DB_URL is not set.\n"
            "Example:\n"
            "  export PIPELINE_DB_URL="
            "'postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'"
        )

    count = load_holidays(Path(args.csv), db_url, args.replace)
    print(f"Loaded {count:,} holiday rows into {TABLE_NAME}.")


if __name__ == "__main__":
    main()
