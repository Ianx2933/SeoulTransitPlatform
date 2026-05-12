"""
Build a coordinate-master patch from resolved service-level mapping.

Input:
- data/processed/bus_stop_location/bus_stop_demand_node_mapping.csv

Output:
- data/processed/bus_stop_location/integrated_bus_stop_location_patch.csv
"""

import pandas as pd
from config import SERVICE_MAPPING_CSV, INTEGRATED_LOCATION_PATCH_CSV, OUTPUT_LOCATION_DIR
from common import clean_df


SAFE_MATCH_METHODS = {
    "curated_ars_and_stop_name_to_node_info",
    "curated_ars_and_standard_node_id_to_node_info",
    "unique_node_info_ars_fallback",
}


def main() -> None:
    """Export one coordinate row per canonical node ID. (canonical_node_id별 좌표 행 생성)"""
    OUTPUT_LOCATION_DIR.mkdir(parents=True, exist_ok=True)
    mapping = clean_df(pd.read_csv(SERVICE_MAPPING_CSV, dtype=str, encoding="utf-8-sig"))
    if mapping.empty:
        raise ValueError("No rows in bus_stop_demand_node_mapping.csv")

    # Keep only safe ARS-first match methods. (ARS 우선 매칭 방식만 patch에 반영)
    mapping = mapping[mapping["match_method"].isin(SAFE_MATCH_METHODS)].copy()
    if mapping.empty:
        raise ValueError("No safe ARS-first rows remain for integrated_bus_stop_location_patch.csv")

    mapping["match_confidence_num"] = pd.to_numeric(mapping["match_confidence"], errors="coerce").fillna(0)

    # Keep only fields required for the integrated coordinate table. (좌표 마스터에 필요한 컬럼만 유지)
    out = mapping[[
        "canonical_node_id", "ars_id", "stop_name", "node_info_stop_name", "lat", "lng", "region_id", "match_method", "match_confidence",
        "match_confidence_num",
    ]].copy()
    out["source_region"] = "node_info_curated_patch"
    out["source_file"] = "od_curated_20251111.csv + node_info.csv"

    # Prefer higher-confidence matches if multiple service rows point to the same canonical node.
    # (같은 canonical_node_id에 여러 행이 있으면 confidence가 높은 행을 우선)
    out = out.sort_values(["canonical_node_id", "match_confidence_num"], ascending=[True, False])
    out = out.drop_duplicates(["canonical_node_id"])
    out = out[[
        "canonical_node_id", "ars_id", "stop_name", "node_info_stop_name", "lat", "lng", "region_id",
        "source_region", "source_file", "match_method", "match_confidence",
    ]]
    out.to_csv(INTEGRATED_LOCATION_PATCH_CSV, index=False, encoding="utf-8-sig")
    print(f"Saved: {INTEGRATED_LOCATION_PATCH_CSV}")
    print(f"Rows: {len(out):,}")


if __name__ == "__main__":
    main()
