"""
Shared cleaning and normalization helpers for bus-stop integration.
"""

import pandas as pd


def clean_df(df: pd.DataFrame) -> pd.DataFrame:
    """Return a copy with normalized column names and cleaned string values."""
    df = df.copy()
    df.columns = [str(c).replace("﻿", "").strip() for c in df.columns]
    for col in df.columns:
        df[col] = clean_series(df[col])
    return df


def clean_series(series: pd.Series) -> pd.Series:
    """Normalize BOM, whitespace, and common null-like values."""
    return (
        series.astype(str)
        .str.replace("﻿", "", regex=False)
        .str.strip()
        .replace({"nan": pd.NA, "None": pd.NA, "": pd.NA, "<NA>": pd.NA})
    )


def norm_ars_id(series: pd.Series) -> pd.Series:
    """Normalize ARS / stop number / demand node ID into a five-character string.

    Rules:
    - Preserve non-numeric placeholder codes such as 0000~ after trimming. 
    - Strip a trailing .0 caused by spreadsheet or CSV numeric conversion.
    - Left-pad numeric values shorter than five digits with zero.
    - Preserve numeric values that are already five or more digits.
    """
    s = clean_series(series)
    s = s.astype("string")
    s = s.str.replace(r"\.0$", "", regex=True).str.strip()

    numeric_mask = s.str.fullmatch(r"\d+", na=False)
    s = s.mask(numeric_mask, s.where(~numeric_mask, s.str.zfill(5)))
    return s.replace({"nan": pd.NA, "none": pd.NA, "": pd.NA, "<na>": pd.NA})


def norm_name(series: pd.Series) -> pd.Series:
    """Apply conservative Korean stop-name normalization for equality matching."""
    return (
        series.astype(str)
        .str.replace("﻿", "", regex=False)
        .str.strip()
        .str.lower()
        .str.replace(r"\s+", "", regex=True)
        .str.replace(".", "", regex=False)
        .str.replace("·", "", regex=False)
        .str.replace("ㆍ", "", regex=False)
        .str.replace("-", "", regex=False)
        .str.replace("_", "", regex=False)
        .replace({"nan": pd.NA, "none": pd.NA, "": pd.NA, "<na>": pd.NA})
    )


def require_columns(df: pd.DataFrame, required: set[str], label: str) -> None:
    """Raise a clear error if required columns are missing."""
    missing = required - set(df.columns)
    if missing:
        raise ValueError(f"{label} lacks required columns: {sorted(missing)}")
