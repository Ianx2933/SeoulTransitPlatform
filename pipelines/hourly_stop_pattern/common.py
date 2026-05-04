"""
Common utilities for Phase 6.3 Local.

This module keeps day-type and database utility logic in one place so that the
pipeline scripts do not duplicate shared rules.
"""

from __future__ import annotations

from sqlalchemy import create_engine


DAY_TYPES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun_holiday"]


def make_engine(db_url: str):
    """
    Create a SQLAlchemy engine.
    """
    return create_engine(db_url)


def normalize_ars(value) -> str:
    """
    Normalize ARS to a 5-digit identifier when possible.

    ARS is treated as an identifier, not a number.
    """
    normalized = str(value).strip()
    if normalized == "" or normalized.lower() == "nan":
        return "00000"
    if not normalized.isdigit():
        return normalized
    return normalized.zfill(5)
