"""Repository-size and schema guard for the frontend administrative boundary asset."""

from __future__ import annotations

import json


def test_frontend_boundary_asset_is_complete_but_display_sized(project_root):
    path = (
        project_root
        / "services"
        / "web-client"
        / "public"
        / "data"
        / "capital_area_admin_dong_4326.geojson"
    )

    assert path.stat().st_size < 10 * 1024 * 1024

    data = json.loads(path.read_text(encoding="utf-8"))
    features = data["features"]

    assert data["type"] == "FeatureCollection"
    # The current demo asset is the 2025-06-30 snapshot (1,208 features).
    # Keep only a lower-bound guard so a legitimate future boundary refresh
    # does not fail merely because the administrative-unit count changed.
    assert len(features) > 1000
    assert {"BASE_DATE", "ADM_CD", "ADM_NM"}.issubset(features[0]["properties"])
    assert all(feature["geometry"]["type"] in {"Polygon", "MultiPolygon"} for feature in features)
