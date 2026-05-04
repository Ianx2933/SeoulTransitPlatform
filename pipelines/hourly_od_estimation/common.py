"""
Shared utilities for Phase 6 hourly OD estimation.

This module intentionally contains only pure helper functions and constants.
Keeping shared logic here prevents duplicated date/ARS/day-type rules across scripts.
"""

from __future__ import annotations

import calendar
from datetime import datetime
from pathlib import Path

import pandas as pd
from sqlalchemy import create_engine


# Seven day-type classes used by the decomposition model.
DAY_TYPES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun_holiday"]

# Sunday is grouped with holidays to stabilize the least-squares decomposition.

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
    Create a SQLAlchemy engine from a database URL.

    Keeping this wrapper makes DB creation consistent across all pipeline scripts.
    """
    return create_engine(db_url)


def normalize_ars(value) -> str:
    """
    Normalize ARS code to a 5-digit identifier when possible.

    ARS is an identifier, not a number. Leading zeros are meaningful.
    If a value is malformed, this function keeps the raw string instead of crashing
    because upstream public data may contain irregular stop codes.
    """
    normalized = str(value).strip()
    if normalized == "" or normalized.lower() == "nan":
        return "00000"
    if not normalized.isdigit():
        return normalized
    return normalized.zfill(5)


def parse_use_ym(value: str) -> tuple[int, int]:
    """
    Parse YYYYMM into (year, month).
    """
    value = str(value).strip()
    if len(value) != 6 or not value.isdigit():
        raise ValueError("use_ym must be YYYYMM, e.g. 202501")
    return int(value[:4]), int(value[4:])


def ym_range(start_ym: str, end_ym: str) -> list[str]:
    """
    Build an inclusive list of YYYYMM values.
    """
    start_year, start_month = parse_use_ym(start_ym)
    end_year, end_month = parse_use_ym(end_ym)

    result = []
    year, month = start_year, start_month

    while (year, month) <= (end_year, end_month):
        result.append(f"{year}{month:02d}")
        month += 1
        if month == 13:
            year += 1
            month = 1

    return result


def load_holidays(holiday_csv: str | None) -> set[str]:
    """
    Load holiday dates from a CSV file.

    Seoul/open-data CSV files often use cp949, so this function tries cp949 first
    and falls back to utf-8-sig. The return value uses YYYY-MM-DD strings to 
    make comparison with datetime.strftime simple and explicit.
    """
    if not holiday_csv:
        return set()

    path = Path(holiday_csv)
    if not path.exists():
        raise FileNotFoundError(f"holiday csv not found: {holiday_csv}")

    try:
        df = pd.read_csv(path, encoding="cp949")
    except UnicodeDecodeError:
        df = pd.read_csv(path, encoding="utf-8-sig")

    date_col = None
    for candidate in ["날짜", "date", "DATE", "일자"]:
        if candidate in df.columns:
            date_col = candidate
            break

    if date_col is None:
        raise ValueError(f"holiday csv must contain a date column. columns={list(df.columns)}")

    dates = pd.to_datetime(df[date_col], errors="coerce").dropna()
    return set(dates.dt.strftime("%Y-%m-%d"))


def day_type_for_date(date: datetime, holidays: set[str]) -> str:
    """
    Convert a date into one of the seven day-type classes.

    Holidays override their original weekday and are merged into sun_holiday.
    """
    date_str = date.strftime("%Y-%m-%d")
    if date_str in holidays:
        return "sun_holiday"
    return DOW_TO_DAY_TYPE[date.weekday()]


def build_month_counts(use_ym: str, holidays: set[str]) -> dict[str, int]:
    """
    Count day-type occurrences within a month.

    This count matrix becomes A in the least-squares equation A x = b.
    Each monthly API value is interpreted as a weighted sum of day-type patterns.
    """
    year, month = parse_use_ym(use_ym)
    counts = {day_type: 0 for day_type in DAY_TYPES}

    last_day = calendar.monthrange(year, month)[1]
    for day in range(1, last_day + 1):
        date = datetime(year, month, day)
        counts[day_type_for_date(date, holidays)] += 1

    return counts


def day_type_from_yyyymmdd(date_yyyymmdd: str, holidays: set[str]) -> str:
    """
    Convert YYYYMMDD into a day-type class.
    """
    date = datetime.strptime(str(date_yyyymmdd), "%Y%m%d")
    return day_type_for_date(date, holidays)
