"""Boundary-condition tests for reusable bus-stop normalization helpers."""

from __future__ import annotations

import pandas as pd

from pipelines.bus_stop_integration.common import clean_series, norm_ars_id, norm_name


def test_ars_normalization_handles_numeric_strings_spreadsheet_suffixes_and_placeholders():
    values = pd.Series(["1234", "01234", "1234.0", "0000~", None])

    result = norm_ars_id(values)

    assert result.iloc[0] == "01234"
    assert result.iloc[1] == "01234"
    assert result.iloc[2] == "01234"
    assert result.iloc[3] == "0000~"
    assert pd.isna(result.iloc[4])


def test_stop_name_normalization_is_conservative_but_format_tolerant():
    values = pd.Series(["서울 시청", "서울·시청", "서울-시청", " 서울_시청 "])

    assert norm_name(values).tolist() == ["서울시청"] * 4


def test_clean_series_treats_common_null_like_values_as_missing():
    values = pd.Series(["", "  ", "nan", "None", "<NA>", "value"])
    result = clean_series(values)

    assert result.isna().tolist() == [True, True, True, True, True, False]
    assert result.iloc[-1] == "value"
