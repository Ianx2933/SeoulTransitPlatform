"""
Build a stop reference table from od_curated_YYYYMMDD.csv.

Input:
- data/processed/od/od_curated_20251111.csv, pipe-separated.

Output:
- data/processed/bus_stop_location/curated_bus_stop_reference.csv

Why this exists
- missing_bus_unique_stops.csv only has demand node_id, and node_name currently equals node_id.
- od_curated contains ARS, standard node code, stop name, route, and sequence.
- This reference is the safest bridge between demand node_id and node_info coordinates.
"""

import pandas as pd
from config import OD_CURATED_CSV, OUTPUT_LOCATION_DIR, CURATED_REFERENCE_CSV, OD_SEP, OD_COLUMNS
from common import clean_df, norm_ars_id, require_columns


def make_side(df: pd.DataFrame, side: str) -> pd.DataFrame:
    """Convert either boarding or alighting stop fields into a common schema."""
    if side == "boarding":
        seq_col = OD_COLUMNS["boarding_seq"]
        ars_col = OD_COLUMNS["boarding_ars"]
        std_col = OD_COLUMNS["boarding_standard_node_id"]
        name_col = OD_COLUMNS["boarding_stop_name"]
    elif side == "alighting":
        seq_col = OD_COLUMNS["alighting_seq"]
        ars_col = OD_COLUMNS["alighting_ars"]
        std_col = OD_COLUMNS["alighting_standard_node_id"]
        name_col = OD_COLUMNS["alighting_stop_name"]
    else:
        raise ValueError(side)

    out = pd.DataFrame({
        "date": df[OD_COLUMNS["date"]],
        "route_name": df[OD_COLUMNS["route_name"]],
        "converted_route_id": df[OD_COLUMNS["converted_route_id"]],
        "seq": df[seq_col],
        "ars_id": df[ars_col],
        "standard_node_id": df[std_col],
        "stop_name": df[name_col],
        "side": side,
        "passengers": df[OD_COLUMNS["passengers"]],
    })
    return out


def main() -> None:
    """Create the curated stop reference CSV."""
    OUTPUT_LOCATION_DIR.mkdir(parents=True, exist_ok=True)
    df = clean_df(pd.read_csv(OD_CURATED_CSV, sep=OD_SEP, dtype=str, encoding="utf-8-sig"))
    require_columns(df, set(OD_COLUMNS.values()), "od_curated")

    # Stack boarding and alighting records into one stop-reference table.
    ref = pd.concat([make_side(df, "boarding"), make_side(df, "alighting")], ignore_index=True)
    ref = clean_df(ref)

    # ARS / stop numbers must use a five-character canonical string.
    # Example: 1234 -> 01234; placeholder codes such as 0000~ are preserved.
    ref["ars_id"] = norm_ars_id(ref["ars_id"])

    # Remove rows that cannot identify either an ARS or a stop name.
    ref = ref[ref["ars_id"].notna() & ref["stop_name"].notna()].copy()
    ref["passengers_num"] = pd.to_numeric(ref["passengers"], errors="coerce").fillna(0).astype("int64")

    group_cols = [
        "route_name",
        "converted_route_id",
        "seq",
        "ars_id",
        "standard_node_id",
        "stop_name",
        "side",
    ]

    # Collapse duplicated OD rows while preserving matching keys and reference volume.
    out = (
        ref.groupby(group_cols, dropna=False, as_index=False)
        .agg(
            reference_rows=("ars_id", "size"),
            passenger_sum=("passengers_num", "sum"),
        )
        .sort_values(["ars_id", "route_name", "seq", "standard_node_id", "side"])
    )

    out.to_csv(CURATED_REFERENCE_CSV, index=False, encoding="utf-8-sig")
    print(f"Saved: {CURATED_REFERENCE_CSV}")
    print(f"Rows: {len(out):,}")
    print(f"Unique ARS: {out['ars_id'].nunique():,}")
    print(f"Unique standard_node_id: {out['standard_node_id'].nunique():,}")


if __name__ == "__main__":
    main()
