"""Executable contracts for the ordered bus-stop coordinate matcher."""

from __future__ import annotations

import pandas as pd
import pytest

from pipelines.bus_stop_integration import resolve_missing_stops_with_curated as resolver
from pipelines.bus_stop_integration.common import norm_name


def make_ref(
    *,
    ars_id: str,
    stop_name: str,
    standard_node_id: str,
    route_name: str = "R1",
) -> dict[str, str]:
    return {
        "route_name": route_name,
        "converted_route_id": "100",
        "seq": "1",
        "ars_id": ars_id,
        "standard_node_id": standard_node_id,
        "stop_name": stop_name,
        "side": "boarding",
        "reference_rows": "1",
        "passenger_sum": "10",
    }


def make_node(
    *,
    ars_id: str,
    canonical_node_id: str,
    stop_name: str,
    candidate_count: int = 1,
    lng: str = "127.0000",
    lat: str = "37.5000",
) -> dict[str, object]:
    return {
        "canonical_node_id": canonical_node_id,
        "node_info_stop_name": stop_name,
        "lng": lng,
        "lat": lat,
        "region_id": "11",
        "node_info_ars_id": ars_id,
        "use_yn": "Y",
        "standard_code_yn": "Y",
        "node_info_stop_name_norm": norm_name(pd.Series([stop_name])).iloc[0],
        "node_info_ars_candidate_count": candidate_count,
    }


def test_stage_1_name_match_has_priority_over_weaker_matches():
    ref = pd.DataFrame([
        make_ref(ars_id="01234", stop_name="서울 시청", standard_node_id="NODE-B")
    ])
    node = pd.DataFrame([
        make_node(
            ars_id="01234",
            canonical_node_id="NODE-A",
            stop_name="서울시청",
            candidate_count=2,
        ),
        make_node(
            ars_id="01234",
            canonical_node_id="NODE-B",
            stop_name="다른정류장",
            candidate_count=2,
        ),
    ])

    matched, unresolved = resolver.attach_coordinates(ref, node)

    assert unresolved.empty
    assert len(matched) == 1
    assert matched.iloc[0]["canonical_node_id"] == "NODE-A"
    assert matched.iloc[0]["match_method"] == "curated_ars_and_stop_name_to_node_info"
    assert matched.iloc[0]["match_confidence"] == pytest.approx(1.00)


def test_stage_2_is_used_only_after_stage_1_fails():
    ref = pd.DataFrame([
        make_ref(ars_id="02345", stop_name="이름불일치", standard_node_id="NODE-2")
    ])
    node = pd.DataFrame([
        make_node(ars_id="02345", canonical_node_id="NODE-2", stop_name="강남역")
    ])

    matched, unresolved = resolver.attach_coordinates(ref, node)

    assert unresolved.empty
    assert len(matched) == 1
    assert matched.iloc[0]["canonical_node_id"] == "NODE-2"
    assert (
        matched.iloc[0]["match_method"]
        == "curated_ars_and_standard_node_id_to_node_info"
    )
    assert matched.iloc[0]["match_confidence"] == pytest.approx(0.95)


def test_unique_ars_fallback_runs_only_after_stronger_matches_fail():
    ref = pd.DataFrame([
        make_ref(ars_id="03456", stop_name="UNKNOWN", standard_node_id="UNKNOWN")
    ])
    node = pd.DataFrame([
        make_node(ars_id="03456", canonical_node_id="NODE-3", stop_name="실제정류장")
    ])

    matched, unresolved = resolver.attach_coordinates(ref, node)

    assert unresolved.empty
    assert len(matched) == 1
    assert matched.iloc[0]["canonical_node_id"] == "NODE-3"
    assert matched.iloc[0]["match_method"] == "unique_node_info_ars_fallback"
    assert matched.iloc[0]["match_confidence"] == pytest.approx(0.80)


def test_unique_ars_fallback_refuses_ambiguous_ars():
    ref = pd.DataFrame([
        make_ref(ars_id="09999", stop_name="UNKNOWN", standard_node_id="UNKNOWN")
    ])
    node = pd.DataFrame([
        make_node(
            ars_id="09999",
            canonical_node_id="NODE-A",
            stop_name="정류장A",
            candidate_count=2,
        ),
        make_node(
            ars_id="09999",
            canonical_node_id="NODE-B",
            stop_name="정류장B",
            candidate_count=2,
        ),
    ])

    matched, unresolved = resolver.attach_coordinates(ref, node)

    assert matched.empty
    assert len(unresolved) == 1
    assert (
        unresolved.iloc[0]["unresolved_reason"]
        == "no_coordinate_candidate_after_ars_first_matching"
    )


def test_provenance_records_only_the_stage_that_resolved_each_row():
    ref = pd.DataFrame([
        make_ref(ars_id="01001", stop_name="정류장1", standard_node_id="WRONG"),
        make_ref(ars_id="01002", stop_name="WRONG", standard_node_id="NODE-2"),
        make_ref(ars_id="01003", stop_name="WRONG", standard_node_id="WRONG"),
    ])
    node = pd.DataFrame([
        make_node(ars_id="01001", canonical_node_id="NODE-1", stop_name="정류장1"),
        make_node(ars_id="01002", canonical_node_id="NODE-2", stop_name="정류장2"),
        make_node(ars_id="01003", canonical_node_id="NODE-3", stop_name="정류장3"),
    ])

    matched, unresolved = resolver.attach_coordinates(ref, node)
    methods = dict(zip(matched["ars_id"], matched["match_method"], strict=True))

    assert unresolved.empty
    assert methods == {
        "01001": "curated_ars_and_stop_name_to_node_info",
        "01002": "curated_ars_and_standard_node_id_to_node_info",
        "01003": "unique_node_info_ars_fallback",
    }


def test_coordinate_loader_rejects_invalid_coordinates_and_counts_valid_candidates(
    tmp_path, monkeypatch
):
    node_info = pd.DataFrame(
        {
            "노드ID": ["A", "B", "C", "D"],
            "노드명": ["정류장A", "정류장B", "정류장C", "정류장D"],
            "좌표X": [127.0, 127.1, 150.0, 127.2],
            "좌표Y": [37.5, 37.6, 37.5, 90.0],
            "지역ID": ["11", "11", "11", "11"],
            "정류장번호": ["1234", "01234", "9999", "8888"],
            "사용여부": ["Y", "Y", "Y", "Y"],
            "표준코드여부(1:표준/0:비표준)": ["Y", "Y", "Y", "Y"],
        }
    )
    csv_path = tmp_path / "node_info.csv"
    node_info.to_csv(csv_path, index=False, encoding="utf-8-sig")
    monkeypatch.setattr(resolver, "NODE_INFO_CSV", csv_path)

    loaded = resolver.load_node_info()

    assert set(loaded["canonical_node_id"]) == {"A", "B"}
    assert set(loaded["node_info_ars_id"]) == {"01234"}
    assert set(loaded["node_info_ars_candidate_count"]) == {2}


def test_stage_1_ambiguous_join_does_not_multiply_reference_cardinality():
    ref = pd.DataFrame([
        make_ref(ars_id="01234", stop_name="서울 시청", standard_node_id="UNKNOWN")
    ])
    node = pd.DataFrame([
        make_node(
            ars_id="01234",
            canonical_node_id="NODE-A",
            stop_name="서울시청",
            candidate_count=2,
        ),
        make_node(
            ars_id="01234",
            canonical_node_id="NODE-B",
            stop_name="서울 시청",
            candidate_count=2,
        ),
    ])

    matched, unresolved = resolver.attach_coordinates(ref, node)

    assert len(matched) + len(unresolved) == len(ref)
    assert matched.empty
    assert len(unresolved) == 1


def test_stage_2_can_disambiguate_an_ambiguous_stage_1_name_match():
    ref = pd.DataFrame([
        make_ref(ars_id="01234", stop_name="서울 시청", standard_node_id="NODE-B")
    ])
    node = pd.DataFrame([
        make_node(
            ars_id="01234",
            canonical_node_id="NODE-A",
            stop_name="서울시청",
            candidate_count=2,
        ),
        make_node(
            ars_id="01234",
            canonical_node_id="NODE-B",
            stop_name="서울 시청",
            candidate_count=2,
        ),
    ])

    matched, unresolved = resolver.attach_coordinates(ref, node)

    assert unresolved.empty
    assert len(matched) == 1
    assert matched.iloc[0]["canonical_node_id"] == "NODE-B"
    assert (
        matched.iloc[0]["match_method"]
        == "curated_ars_and_standard_node_id_to_node_info"
    )
