"""
Common utilities for Phase 6.5 lightweight subway integration.

Keep parsing, encoding fallback, and day-type classification here so pipeline scripts stay focused.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine


DOW_TO_DAY_TYPE = {
    0: "mon",
    1: "tue",
    2: "wed",
    3: "thu",
    4: "fri",
    5: "sat",
    6: "sun_holiday",
}


def make_engine(db_url: str):
    """
    Create a SQLAlchemy engine.
    """
    return create_engine(db_url)


def read_csv_flexible(path: str | Path) -> pd.DataFrame:
    """
    Read CSV using common Korean public-data encodings.
    """
    path = Path(path)
    encodings = ["utf-8-sig", "cp949", "euc-kr", "utf-8"]

    last_error = None
    for encoding in encodings:
        try:
            return pd.read_csv(path, encoding=encoding)
        except UnicodeDecodeError as error:
            last_error = error

    raise RuntimeError(f"Failed to read CSV: {path}; last_error={last_error}")


def load_holidays(holiday_csv: str | None) -> set[str]:
    """
    Load holiday dates as YYYY-MM-DD strings.
    """
    if not holiday_csv:
        return set()

    path = Path(holiday_csv)
    if not path.exists():
        raise FileNotFoundError(f"holiday csv not found: {holiday_csv}")

    df = read_csv_flexible(path)

    date_col = None
    for candidate in ["날짜", "date", "DATE", "일자"]:
        if candidate in df.columns:
            date_col = candidate
            break

    if date_col is None:
        raise ValueError(f"holiday csv must contain a date column. columns={list(df.columns)}")

    dates = pd.to_datetime(df[date_col], errors="coerce").dropna()
    return set(dates.dt.strftime("%Y-%m-%d"))


def normalize_date(value) -> str:
    """
    Normalize date values into YYYYMMDD.
    """
    raw = str(value).strip()

    if raw.isdigit() and len(raw) == 8:
        return raw

    parsed = pd.to_datetime(raw, errors="coerce")
    if pd.isna(parsed):
        raise ValueError(f"invalid date value: {value}")

    return parsed.strftime("%Y%m%d")


def day_type_from_yyyymmdd(date_yyyymmdd: str, holidays: set[str]) -> str:
    """
    Convert YYYYMMDD into a day-type class.
    """
    date = datetime.strptime(str(date_yyyymmdd), "%Y%m%d")
    date_str = date.strftime("%Y-%m-%d")

    if date_str in holidays:
        return "sun_holiday"

    return DOW_TO_DAY_TYPE[date.weekday()]


def normalize_hour(value) -> int:
    """
    Normalize hour values to 0-23 integer.
    """
    hour = int(value)
    if not 0 <= hour <= 23:
        raise ValueError(f"hour must be between 0 and 23: {value}")
    return hour
