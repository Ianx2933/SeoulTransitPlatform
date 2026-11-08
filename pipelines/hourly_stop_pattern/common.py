"""
Common utilities for Phase 6.3 Local.

This module keeps day-type and database utility logic in one place so that the
pipeline scripts do not duplicate shared rules.
"""

from __future__ import annotations

import io
import os

import pandas as pd
from sqlalchemy import create_engine, text


DAY_TYPES = ["mon", "tue", "wed", "thu", "fri", "sat", "sun_holiday"]


def make_engine(db_url: str):
    """
    Create a SQLAlchemy engine.
    """
    return create_engine(db_url, executemany_mode="values_plus_batch")


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


# --- Rebuilding into a copy that replaces the table only after validation ---
#
# The weekly workflow sets PIPELINE_TABLE_TARGET=next. Each rebuilt table is then
# written to <table>__next, the steps after it read that copy, validation checks
# the copies, and publish_rebuild.py swaps them in. Until then the API keeps
# reading the previous, validated tables, and since the copies are filled from
# empty with COPY, a rebuild leaves no dead rows behind. Without the variable,
# scripts write the table itself as before, which is what local runs use.

TABLE_TARGET_ENV = "PIPELINE_TABLE_TARGET"
NEXT_SUFFIX = "__next"


def table_target(value: str | None = None) -> str:
    """'live' or 'next', from the argument, else PIPELINE_TABLE_TARGET, else live."""
    target = (value or os.environ.get(TABLE_TARGET_ENV) or "live").strip().lower()
    if target not in ("live", "next"):
        raise SystemExit(f"{TABLE_TARGET_ENV} must be 'live' or 'next', not {target!r}")
    return target


def target_table(table: str, target: str) -> str:
    return table + NEXT_SUFFIX if target == "next" else table


def prepare_next_table(conn, table: str, keep_where: str | None = None,
                       params: dict | None = None) -> str:
    """
    Create an empty <table>__next with the table's columns, keys and indexes.

    keep_where copies the rows of the current table that this rebuild does not
    replace, such as estimates for other dates.
    """
    nxt = table + NEXT_SUFFIX
    conn.exec_driver_sql(f"DROP TABLE IF EXISTS {nxt}")
    conn.exec_driver_sql(f"CREATE TABLE {nxt} (LIKE {table} INCLUDING ALL)")
    if keep_where:
        conn.execute(text(f"INSERT INTO {nxt} SELECT * FROM {table} WHERE {keep_where}"),
                     params or {})
    return nxt


def copy_frame(conn, frame: pd.DataFrame, table: str, columns: list[str],
               chunk_rows: int = 500_000) -> int:
    """
    Append rows with COPY. Floats are written in the shortest form that reads back
    to the same double, so stored values match a parameterised INSERT exactly.
    """
    if frame.empty:
        return 0
    cols = ", ".join(columns)
    cursor = conn.connection.cursor()
    try:
        for start in range(0, len(frame), chunk_rows):
            buffer = io.StringIO()
            frame.iloc[start:start + chunk_rows][columns].to_csv(buffer, index=False, header=False)
            buffer.seek(0)
            cursor.copy_expert(f"COPY {table} ({cols}) FROM STDIN WITH (FORMAT csv)", buffer)
    finally:
        cursor.close()
    return len(frame)
