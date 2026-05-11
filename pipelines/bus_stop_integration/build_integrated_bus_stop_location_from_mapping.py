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


def main() -> None:
    """Export one coordinate row per canonical node ID."""
    OUTPUT_LOCATION_DIR.mkdir(parents=True, exist_ok=True)
    mapping = clean_df(pd.read_csv(SERVICE_MAPPING_CSV, dtype=str, encoding="utf-8-sig"))
    if mapping.empty:
        raise ValueError("No rows in bus_stop_demand_node_mapping.csv")

    # Keep only fields required for the integrated coordinate table.
    out = mapping[[
        "canonical_node_id", "ars_id", "stop_name", "node_info_stop_name", "lat", "lng", "region_id", "match_method", "match_confidence"
    ]].copy()
    out["source_region"] = "node_info_curated_patch"
    out["source_file"] = "od_curated_20251111.csv + node_info.csv"

    # Prefer higher-confidence matches if multiple service rows point to the same canonical node.
    out = out.sort_values(["canonical_node_id", "match_confidence"], ascending=[True, False])
    out = out.drop_duplicates(["canonical_node_id"])
    out = out[[
        "canonical_node_id", "ars_id", "stop_name", "node_info_stop_name", "lat", "lng", "region_id",
        "source_region", "source_file", "match_method", "match_confidence"
    ]]
    out.to_csv(INTEGRATED_LOCATION_PATCH_CSV, index=False, encoding="utf-8-sig")
    print(f"Saved: {INTEGRATED_LOCATION_PATCH_CSV}")
    print(f"Rows: {len(out):,}")


if __name__ == "__main__":
    main()
