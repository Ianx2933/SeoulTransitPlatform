# Administrative boundary display asset

`capital_area_admin_dong_4326.geojson` is a **display-optimized** boundary asset
for the Leaflet client. The current `2025-06-30` snapshot keeps all 1,208
administrative-dong features and the original `ADM_CD`, `ADM_NM`, and `BASE_DATE`
properties, but its geometries are
topology-preserving simplified at `0.0001°` for repository and browser size.

The analytical PostGIS layer does **not** use this file. Database queries use the
boundary table loaded separately into PostGIS, so map rendering precision and
analytical spatial precision remain intentionally separated.

The unsimplified working extract was about 43 MB and is not distributed in the
portfolio repository. If that larger file was already committed in Git history,
replacing the working-tree file will not shrink old commits; history cleanup is
a separate repository-maintenance operation.
