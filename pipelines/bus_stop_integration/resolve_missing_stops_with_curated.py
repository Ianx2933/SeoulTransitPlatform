"""
Resolve missing bus-stop coordinates using curated OD stop metadata and ARS-first matching.

Priority steps:
1. curated.ars_id + curated.stop_name = node_info.정류장번호 + node_info.노드명.
2. curated.ars_id + curated.standard_node_id = node_info.정류장번호 + node_info.노드ID.
3. ARS is unique in node_info as a conservative fallback.

Important:
- Pure standard_node_id matching is intentionally NOT used.
- The demand node ID is treated as an ARS-like stop code and normalized to five digits.

Outputs:
- bus_stop_demand_node_mapping.csv: service_id + demand_node_id -> canonical_node_id.
- bus_stop_ars_node_mapping.csv: demand_node_id -> canonical_node_id only where ARS maps uniquely.
- bus_stop_mapping_ambiguous.csv, bus_stop_mapping_unresolved.csv, missing_bus_virtual_or_unknown_stops.csv, curated_mapping_summary.csv.
"""

import pandas as pd
from config import (
    MISSING_UNIQUE_STOPS_CSV,
    NODE_INFO_CSV,
    CURATED_REFERENCE_CSV,
    OUTPUT_MISSING_DIR,
    OUTPUT_LOCATION_DIR,
    SERVICE_MAPPING_CSV,
    ARS_MAPPING_CSV,
    AMBIGUOUS_CSV,
    UNRESOLVED_CSV,
    SUMMARY_CSV,
    NODE_INFO_COLUMNS,
    LAT_MIN,
    LAT_MAX,
    LNG_MIN,
    LNG_MAX,
)
from common import clean_df, norm_ars_id, norm_name, require_columns


def load_node_info() -> pd.DataFrame:
    """Load node_info.csv and keep coordinate-valid stop rows."""
    node = clean_df(pd.read_csv(NODE_INFO_CSV, dtype=str, encoding="utf-8-sig"))
    require_columns(node, set(NODE_INFO_COLUMNS.values()), "node_info")

    node = node[[
        NODE_INFO_COLUMNS["canonical_node_id"],
        NODE_INFO_COLUMNS["stop_name"],
        NODE_INFO_COLUMNS["lng"],
        NODE_INFO_COLUMNS["lat"],
        NODE_INFO_COLUMNS["region_id"],
        NODE_INFO_COLUMNS["ars_id"],
        NODE_INFO_COLUMNS["use_yn"],
        NODE_INFO_COLUMNS["standard_code_yn"],
    ]].rename(columns={
        NODE_INFO_COLUMNS["canonical_node_id"]: "canonical_node_id",
        NODE_INFO_COLUMNS["stop_name"]: "node_info_stop_name",
        NODE_INFO_COLUMNS["lng"]: "lng",
        NODE_INFO_COLUMNS["lat"]: "lat",
        NODE_INFO_COLUMNS["region_id"]: "region_id",
        NODE_INFO_COLUMNS["ars_id"]: "node_info_ars_id",
        NODE_INFO_COLUMNS["use_yn"]: "use_yn",
        NODE_INFO_COLUMNS["standard_code_yn"]: "standard_code_yn",
    })

    # Normalize ARS values to five-character strings.
    node["node_info_ars_id"] = norm_ars_id(node["node_info_ars_id"])
    node["node_info_stop_name_norm"] = norm_name(node["node_info_stop_name"])
    node["lat_num"] = pd.to_numeric(node["lat"], errors="coerce")
    node["lng_num"] = pd.to_numeric(node["lng"], errors="coerce")

    # Keep only plausible coordinates for Korea and nearby service areas. (한국 및 인근 서비스권 좌표만 유지)
    node = node[
        node["lat_num"].between(LAT_MIN, LAT_MAX)
        & node["lng_num"].between(LNG_MIN, LNG_MAX)
        & node["node_info_ars_id"].notna()
    ].copy()

    # Count ARS candidates after coordinate filtering.
    ars_counts = (
        node.groupby("node_info_ars_id", dropna=False)["canonical_node_id"]
        .nunique()
        .reset_index(name="node_info_ars_candidate_count")
    )
    node = node.merge(ars_counts, on="node_info_ars_id", how="left")
    return node


def _split_matched_rows(
    merged: pd.DataFrame,
    ref_columns: list[str],
    already_resolved_ref_ids: set,
) -> tuple[pd.DataFrame, pd.DataFrame, set]:
    """Split a merged candidate table into matched rows and remaining refs."""
    matched = merged[merged["canonical_node_id"].notna()].copy()
    matched_ref_ids = set(matched["_ref_row_id"].dropna())
    resolved_ref_ids = already_resolved_ref_ids | matched_ref_ids

    remaining = (
        merged[~merged["_ref_row_id"].isin(resolved_ref_ids)][ref_columns]
        .drop_duplicates("_ref_row_id")
        .copy()
    )
    return matched, remaining, resolved_ref_ids


def attach_coordinates(ref: pd.DataFrame, node: pd.DataFrame) -> tuple[pd.DataFrame, pd.DataFrame]:
    """Attach coordinates using ARS-first ordered matching rules."""
    ref = ref.copy().reset_index(drop=True)
    ref["_ref_row_id"] = range(len(ref))
    ref["curated_stop_name_norm"] = norm_name(ref["stop_name"])
    ref_columns = list(ref.columns)
    resolved_ref_ids: set = set()

    # 1) Highest-confidence match: ARS + normalized stop name.
    by_name = ref.merge(
        node,
        left_on=["ars_id", "curated_stop_name_norm"],
        right_on=["node_info_ars_id", "node_info_stop_name_norm"],
        how="left",
    )
    by_name["match_method"] = "curated_ars_and_stop_name_to_node_info"
    by_name["match_confidence"] = 1.00
    matched_name, remaining, resolved_ref_ids = _split_matched_rows(by_name, ref_columns, resolved_ref_ids)

    # 2) Supporting match: ARS + standard node ID. This never uses standard_node_id alone.
    by_ars_and_standard = remaining.merge(
        node,
        left_on=["ars_id", "standard_node_id"],
        right_on=["node_info_ars_id", "canonical_node_id"],
        how="left",
    )
    by_ars_and_standard["match_method"] = "curated_ars_and_standard_node_id_to_node_info"
    by_ars_and_standard["match_confidence"] = 0.95
    matched_standard, remaining, resolved_ref_ids = _split_matched_rows(
        by_ars_and_standard,
        ref_columns,
        resolved_ref_ids,
    )

    # 3) Conservative fallback: ARS has exactly one coordinate-valid candidate in node_info.
    unique_ars = node[node["node_info_ars_candidate_count"] == 1].copy()
    by_unique_ars = remaining.merge(
        unique_ars,
        left_on="ars_id",
        right_on="node_info_ars_id",
        how="left",
    )
    by_unique_ars["match_method"] = "unique_node_info_ars_fallback"
    by_unique_ars["match_confidence"] = 0.80
    matched_unique_ars, remaining_after_all, _ = _split_matched_rows(
        by_unique_ars,
        ref_columns,
        resolved_ref_ids,
    )

    unresolved = remaining_after_all.copy()
    unresolved["unresolved_reason"] = "no_coordinate_candidate_after_ars_first_matching"

    all_matched = pd.concat(
        [matched_name, matched_standard, matched_unique_ars],
        ignore_index=True,
        sort=False,
    )

    # Remove helper columns that should not leak into final outputs.
    for df in (all_matched, unresolved):
        if "_ref_row_id" in df.columns:
            df.drop(columns=["_ref_row_id"], inplace=True)

    return all_matched, unresolved


def make_service_rows(candidates: pd.DataFrame) -> pd.DataFrame:
    """Expand matched stop candidates into service-level mapping rows."""
    base_cols = [
        "route_name", "converted_route_id", "seq", "ars_id", "standard_node_id", "stop_name", "side",
        "reference_rows", "passenger_sum", "canonical_node_id", "node_info_stop_name", "lng", "lat",
        "region_id", "node_info_ars_id", "use_yn", "standard_code_yn", "match_method", "match_confidence",
        "node_info_ars_candidate_count",
    ]
    candidates = candidates[base_cols].copy()

    rows = []
    for service_col, service_type in [("route_name", "route_name"), ("converted_route_id", "converted_route_id")]:
        part = candidates[candidates[service_col].notna()].copy()
        if part.empty:
            continue
        part["service_id"] = part[service_col]
        part["service_id_type"] = service_type
        rows.append(part)

    if not rows:
        return pd.DataFrame()

    out = pd.concat(rows, ignore_index=True, sort=False)
    out = out.rename(columns={"ars_id": "demand_node_id"})
    out["ars_id"] = out["demand_node_id"]
    out = out.drop_duplicates([
        "service_id", "service_id_type", "demand_node_id", "canonical_node_id", "stop_name", "match_method"
    ])
    return out


def main() -> None:
    """Resolve missing stop IDs and write mapping, ambiguous, unresolved, and summary outputs."""
    OUTPUT_MISSING_DIR.mkdir(parents=True, exist_ok=True)
    OUTPUT_LOCATION_DIR.mkdir(parents=True, exist_ok=True)

    missing = clean_df(pd.read_csv(MISSING_UNIQUE_STOPS_CSV, dtype=str, encoding="utf-8-sig"))
    require_columns(missing, {"node_id", "node_name", "route_count", "row_count"}, "missing_bus_unique_stops")

    # demand node_id is an ARS-like stop code.
    missing["node_id"] = norm_ars_id(missing["node_id"])
    missing["node_name"] = norm_ars_id(missing["node_name"])

    virtual_mask = missing["node_id"].str.contains("~", regex=False, na=False)
    virtual = missing[virtual_mask].copy()
    virtual["reason"] = "virtual_unknown_or_curated_placeholder_excluded_from_coordinate_matching"
    virtual.to_csv(OUTPUT_MISSING_DIR / "missing_bus_virtual_or_unknown_stops.csv", index=False, encoding="utf-8-sig")

    target = missing[~virtual_mask].copy()
    target_ids = set(target["node_id"].dropna())

    ref = clean_df(pd.read_csv(CURATED_REFERENCE_CSV, dtype=str, encoding="utf-8-sig"))
    require_columns(
        ref,
        {"route_name", "converted_route_id", "seq", "ars_id", "standard_node_id", "stop_name", "side", "reference_rows", "passenger_sum"},
        "curated_reference",
    )
    ref["ars_id"] = norm_ars_id(ref["ars_id"])
    ref = ref[ref["ars_id"].isin(target_ids)].copy()

    node = load_node_info()
    matched_candidates, unresolved_candidates = attach_coordinates(ref, node)
    service_rows = make_service_rows(matched_candidates)

    # Keep only service_id + demand_node_id pairs that resolve to exactly one canonical node.
    if service_rows.empty:
        resolved_service = service_rows
        ambiguous_service = service_rows
    else:
        counts = (
            service_rows.groupby(["service_id", "service_id_type", "demand_node_id"], dropna=False)["canonical_node_id"]
            .nunique()
            .reset_index(name="canonical_candidate_count")
        )
        service_rows = service_rows.merge(counts, on=["service_id", "service_id_type", "demand_node_id"], how="left")
        resolved_service = service_rows[service_rows["canonical_candidate_count"] == 1].copy()
        ambiguous_service = service_rows[service_rows["canonical_candidate_count"] > 1].copy()

    resolved_cols = [
        "service_id", "service_id_type", "route_name", "converted_route_id", "demand_node_id", "canonical_node_id",
        "ars_id", "stop_name", "node_info_stop_name", "lat", "lng", "region_id", "seq", "side",
        "match_method", "match_confidence", "reference_rows", "passenger_sum", "canonical_candidate_count",
        "node_info_ars_candidate_count",
    ]
    resolved_service = resolved_service[resolved_cols].drop_duplicates() if not resolved_service.empty else resolved_service

    resolved_service.to_csv(SERVICE_MAPPING_CSV, index=False, encoding="utf-8-sig")
    ambiguous_service.to_csv(AMBIGUOUS_CSV, index=False, encoding="utf-8-sig")

    # Build an ARS-level fallback table only when demand_node_id maps to exactly one canonical node.
    ars_candidates = matched_candidates.copy().rename(columns={"ars_id": "demand_node_id"})
    if ars_candidates.empty:
        ars_mapping = pd.DataFrame()
    else:
        ars_counts = (
            ars_candidates.groupby("demand_node_id", dropna=False)["canonical_node_id"]
            .nunique()
            .reset_index(name="canonical_candidate_count")
        )
        ars_candidates = ars_candidates.merge(ars_counts, on="demand_node_id", how="left")
        ars_mapping = ars_candidates[ars_candidates["canonical_candidate_count"] == 1].copy()
        ars_mapping = ars_mapping[[
            "demand_node_id", "canonical_node_id", "stop_name", "node_info_stop_name", "lat", "lng", "region_id",
            "node_info_ars_id", "match_method", "match_confidence", "canonical_candidate_count",
            "node_info_ars_candidate_count",
        ]].drop_duplicates(["demand_node_id", "canonical_node_id"])
    ars_mapping.to_csv(ARS_MAPPING_CSV, index=False, encoding="utf-8-sig")

    # Unresolved target IDs include IDs not found in curated plus refs without coordinate candidates.
    seen_ids = set(matched_candidates["ars_id"].dropna()) if not matched_candidates.empty else set()
    missing_in_curated = target[~target["node_id"].isin(set(ref["ars_id"].dropna()))].copy()
    missing_in_curated["unresolved_reason"] = "missing_node_id_not_found_in_curated_ars"
    unresolved_ids = target[~target["node_id"].isin(seen_ids)].copy()
    unresolved_ids["unresolved_reason"] = "no_resolved_coordinate_candidate"

    unresolved_out = pd.concat([
        unresolved_candidates,
        missing_in_curated.rename(columns={"node_id": "demand_node_id"}),
        unresolved_ids.rename(columns={"node_id": "demand_node_id"}),
    ], ignore_index=True, sort=False).drop_duplicates()
    unresolved_out.to_csv(UNRESOLVED_CSV, index=False, encoding="utf-8-sig")

    summary = pd.DataFrame([
        {"metric": "missing_unique_stop_ids_total", "value": int(missing["node_id"].nunique())},
        {"metric": "virtual_or_unknown_ids_excluded", "value": int(virtual["node_id"].nunique())},
        {"metric": "target_stop_ids", "value": int(len(target_ids))},
        {"metric": "target_ids_found_in_curated", "value": int(ref["ars_id"].nunique())},
        {"metric": "target_ids_not_found_in_curated", "value": int(len(target_ids - set(ref["ars_id"].dropna())))},
        {"metric": "target_ids_with_any_coordinate_candidate", "value": int(matched_candidates["ars_id"].nunique()) if not matched_candidates.empty else 0},
        {"metric": "ars_level_unique_resolved_ids", "value": int(ars_mapping["demand_node_id"].nunique()) if not ars_mapping.empty else 0},
        {"metric": "service_level_resolved_rows", "value": int(len(resolved_service))},
        {"metric": "service_level_ambiguous_rows", "value": int(len(ambiguous_service))},
    ])
    summary.to_csv(SUMMARY_CSV, index=False, encoding="utf-8-sig")

    print(summary.to_string(index=False))
    print(f"Saved: {SERVICE_MAPPING_CSV}")
    print(f"Saved: {ARS_MAPPING_CSV}")
    print(f"Saved: {AMBIGUOUS_CSV}")
    print(f"Saved: {UNRESOLVED_CSV}")


if __name__ == "__main__":
    main()
