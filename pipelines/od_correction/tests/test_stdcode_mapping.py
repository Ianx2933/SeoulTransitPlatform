"""Standard code mapping must follow the stop, not the position on the route."""

import sys
from pathlib import Path

import pandas as pd
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "src"))

from od_correction_pipeline import ODCorrectionPipeline, PipelineConfig, ars5  # noqa: E402


# Reference made when route 101 had stops A, B, C at positions 1-3.
REFERENCE = [
    # seq, ars, code, name
    (1, "01001", "100000001", "A"),
    (2, "01002", "100000002", "B"),
    (3, "01003", "100000003", "C"),
]

# Later raw data: a new stop X was added at position 2, so B and C moved down.
RAW = [
    (1, 1001, "A"),
    (2, 1099, "X"),
    (3, 1002, "B"),
    (4, 1003, "C"),
]

MASTER = [("01001", "100000001"), ("01002", "100000002"),
          ("01003", "100000003"), ("01099", "100000099")]


def write_inputs(tmp_path, raw=RAW, master=MASTER):
    ref_rows = []
    for (bs, ba, bc, bn) in REFERENCE:
        for (as_, aa, ac, an) in REFERENCE:
            if as_ > bs:
                ref_rows.append(["25/10/14", "101", "1001001", bs, ba, bc, bn,
                                 as_, aa, ac, an, 1])
    ref = pd.DataFrame(ref_rows, columns=ODCorrectionPipeline.REFERENCE_REQUIRED_COLUMNS)
    ref_path = tmp_path / "ref.csv"
    ref.to_csv(ref_path, sep="|", index=False, encoding="utf-8-sig")

    raw_rows = []
    for (bs, ba, bn) in raw:
        for (as_, aa, an) in raw:
            if as_ > bs:
                raw_rows.append(["25/11/11", "101", ba, bn, aa, an, bs, as_, 1])
    raw_df = pd.DataFrame(raw_rows, columns=ODCorrectionPipeline.RAW_REQUIRED_COLUMNS)
    raw_path = tmp_path / "raw.csv"
    raw_df.to_csv(raw_path, sep=",", index=False, encoding="cp949")

    master_path = None
    if master is not None:
        master_path = tmp_path / "master.csv"
        pd.DataFrame(master, columns=["정류장번호", "노드id"]).to_csv(
            master_path, sep="|", index=False, encoding="utf-8"
        )

    return PipelineConfig(
        raw_path=raw_path,
        reference_path=ref_path,
        output_path=tmp_path / "out.csv",
        stop_master_path=master_path,
    )


def boarding_codes(pipeline):
    df = pipeline.curated_df
    return dict(zip(df["승차_정류장ARS"], df["승차_정류장표준코드"]))


def run(config):
    pipeline = ODCorrectionPipeline(config)
    pipeline.run()
    return pipeline


def test_shifted_route_gets_each_stops_own_code(tmp_path, capsys):
    codes = boarding_codes(run(write_inputs(tmp_path)))
    assert codes["01002"] == "100000002"   # B, now at position 3
    assert codes["01099"] == "100000099"   # X, new stop
    assert len(set(codes.values())) == len(codes)


def test_without_master_shifted_positions_stay_empty_not_wrong(tmp_path, capsys):
    codes = boarding_codes(run(write_inputs(tmp_path, master=None)))
    assert codes["01001"] == "100000001"   # position and ARS both match the reference
    # B sits where the reference had C. The old join gave B the code of C.
    assert codes["01002"] != "100000003"
    assert codes["01099"] == ""


def test_name_fill_does_not_cross_to_a_stop_with_its_own_ars(tmp_path, capsys):
    # Two different stops named "A": the new one (ARS 01200) must not borrow
    # the code of ARS 01001 just because the name matches.
    raw = [(1, 1001, "A"), (2, 1200, "A"), (3, 1002, "B")]
    codes = boarding_codes(run(write_inputs(tmp_path, raw=raw, master=None)))
    assert codes["01200"] == ""


def test_validation_fails_on_code_that_belongs_to_another_stop():
    pipeline = ODCorrectionPipeline(PipelineConfig(Path("r"), Path("f"), Path("o")))
    pipeline.stop_master = {"01002": "100000002"}
    df = pd.DataFrame({
        "승차_정류장ARS": ["01002"], "승차_정류장표준코드": ["100000003"],
        "하차_정류장ARS": [""], "하차_정류장표준코드": [""],
    })
    with pytest.raises(ValueError, match="disagree"):
        pipeline.check_codes_against_master(df)


def test_ambiguous_ars_is_left_out_of_the_master():
    master = pd.DataFrame({
        "정류장번호": ["1001", "01002", "01002"],
        "노드id": ["100000001", "100000002", "100000009"],
    })
    assert ODCorrectionPipeline.build_stop_master(master) == {"01001": "100000001"}


def test_ars5_pads_and_strips_float_suffix():
    assert ars5(pd.Series(["2116", "2116.0", "", None, "10.05"])).tolist() == [
        "02116", "02116", "", "", "10.05",
    ]


def test_placeholder_ars_is_treated_as_missing():
    assert ars5(pd.Series(["00000", "0", "0.0"])).tolist() == ["", "", ""]


def test_same_ars_fill_does_not_spread_through_placeholder():
    # Two different depots share ARS 00000; one known code must not reach the other.
    df = pd.DataFrame({
        "ars": ["00000", "00000", "0"],
        "code": ["277102436", "", ""],
    })
    result = ODCorrectionPipeline._fill_by_key(df, "ars", "code")
    assert result["code"].tolist() == ["277102436", "", ""]


def test_cross_fill_does_not_spread_through_placeholder():
    df = pd.DataFrame({
        "b_ars": ["00000", "01001"],
        "b_code": ["277102436", "100000001"],
        "a_ars": ["01001", "00000"],
        "a_code": ["", ""],
    })
    result = ODCorrectionPipeline._fill_by_cross_reference(df, "b_ars", "b_code", "a_ars", "a_code")
    assert result["a_code"].tolist() == ["100000001", ""]


def test_name_fill_applies_to_placeholder_ars():
    df = pd.DataFrame({
        "ars": ["00000", "00000"],
        "name": ["중부운수앞(가상)", "중부운수앞(가상)"],
        "code": ["277000001", ""],
    })
    result = ODCorrectionPipeline._fill_by_name(df, "name", "code", "ars")
    assert result["code"].tolist() == ["277000001", "277000001"]
