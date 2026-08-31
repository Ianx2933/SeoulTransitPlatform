"""Tests for OD identifier recovery, precedence, and ambiguity handling."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

from pipelines.od_correction.src.od_correction_pipeline import ODCorrectionPipeline, PipelineConfig


def make_pipeline() -> ODCorrectionPipeline:
    return ODCorrectionPipeline(
        PipelineConfig(
            raw_path=Path("raw.csv"),
            reference_path=Path("reference.csv"),
            output_path=Path("output.csv"),
        )
    )


def test_same_ars_fallback_fills_only_missing_codes_and_preserves_existing_values():
    df = pd.DataFrame(
        {
            "ars": ["01001", "01001", "01002"],
            "code": ["111111111", "", "222222222"],
        }
    )

    result = ODCorrectionPipeline._fill_by_key(df, "ars", "code")

    assert result["code"].tolist() == ["111111111", "111111111", "222222222"]
    assert len(result) == len(df)


def test_same_ars_fallback_does_not_choose_arbitrarily_when_key_is_ambiguous():
    df = pd.DataFrame(
        {
            "ars": ["01001", "01001", "01001"],
            "code": ["111111111", "222222222", ""],
        }
    )

    result = ODCorrectionPipeline._fill_by_key(df, "ars", "code")

    assert result.iloc[2]["code"] == ""
    assert len(result) == len(df)


def test_stop_name_fallback_does_not_resolve_ambiguous_name():
    df = pd.DataFrame(
        {
            "stop_name": ["중앙시장", "중앙시장", "중앙시장"],
            "code": ["111111111", "222222222", ""],
        }
    )

    result = ODCorrectionPipeline._fill_by_name(df, "stop_name", "code")

    assert result.iloc[2]["code"] == ""
    assert len(result) == len(df)


def test_cross_reference_uses_only_unambiguous_source_ars():
    df = pd.DataFrame(
        {
            "boarding_ars": ["01001", "01001", "03003"],
            "boarding_code": ["111111111", "222222222", "333333333"],
            "alighting_ars": ["99999", "99998", "01001"],
            "alighting_code": ["", "", ""],
        }
    )

    result = ODCorrectionPipeline._fill_by_cross_reference(
        df,
        source_ars_col="boarding_ars",
        source_code_col="boarding_code",
        target_ars_col="alighting_ars",
        target_code_col="alighting_code",
    )

    assert result.iloc[2]["alighting_code"] == ""
    assert len(result) == len(df)


def test_stronger_stage_result_is_not_overwritten_by_weaker_stop_name_stage():
    df = pd.DataFrame(
        {
            "ars": ["01001", "01001", "02002"],
            "stop_name": ["중앙시장", "다른이름", "중앙시장"],
            "code": ["111111111", "", "222222222"],
        }
    )

    after_ars = ODCorrectionPipeline._fill_by_key(df, "ars", "code")
    after_name = ODCorrectionPipeline._fill_by_name(after_ars, "stop_name", "code")

    assert after_ars.iloc[1]["code"] == "111111111"
    assert after_name.iloc[1]["code"] == "111111111"


def test_four_stage_helpers_preserve_record_cardinality():
    df = pd.DataFrame(
        {
            "boarding_ars": ["01001", "01001", "02002", "03003"],
            "boarding_name": ["A", "B", "C", "D"],
            "boarding_code": ["111111111", "", "222222222", ""],
            "alighting_ars": ["04004", "05005", "03003", "06006"],
            "alighting_name": ["E", "F", "D", "G"],
            "alighting_code": ["444444444", "", "", "666666666"],
        }
    )
    before = len(df)

    result = ODCorrectionPipeline._fill_by_key(df, "boarding_ars", "boarding_code")
    result = ODCorrectionPipeline._fill_by_name(result, "boarding_name", "boarding_code")
    result = ODCorrectionPipeline._fill_by_cross_reference(
        result,
        source_ars_col="alighting_ars",
        source_code_col="alighting_code",
        target_ars_col="boarding_ars",
        target_code_col="boarding_code",
    )

    assert len(result) == before


def test_known_excel_corrupted_route_names_are_repaired():
    pipeline = make_pipeline()
    result = pipeline.normalize_route_name(pd.Series(["411", "40", "Jan-01", "143"]))

    assert result.tolist() == ["0411", "040", "101", "143"]


def test_final_code_format_zero_pads_ars_and_standard_codes():
    pipeline = make_pipeline()
    pipeline.raw_df = pd.DataFrame(
        {
            "승차_정류장ARS": ["1234"],
            "하차_정류장ARS": ["987"],
            "승차_정류장표준코드": ["12345678"],
            "하차_정류장표준코드": ["1234567"],
        }
    )

    pipeline.finalize_code_formats()

    assert pipeline.raw_df.iloc[0].to_dict() == {
        "승차_정류장ARS": "01234",
        "하차_정류장ARS": "00987",
        "승차_정류장표준코드": "012345678",
        "하차_정류장표준코드": "001234567",
    }
