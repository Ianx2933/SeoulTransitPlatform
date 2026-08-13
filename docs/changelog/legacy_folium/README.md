# Legacy — Folium Visualisation

The map generation layer used before the React/Leaflet web client.

## Contents

| File | Purpose |
|---|---|
| `make_map.py` | Generated interactive congestion maps by calling the API and rendering with Folium |

The generated output — `congestion_map.html` — is not kept. It was 11.9 MB
with all data inlined by Folium, and it is regenerable from this script. The
maps it produced are preserved as images in

## Why it was replaced

Folium writes a static HTML file with every data point embedded in the
document. That works for a one-off analysis artefact and stops working when
the requirement becomes:

- filtering by mode, day type, and hour without regenerating the file;
- querying demand for an arbitrary radius or administrative district;
- serving many users from a deployed instance rather than sharing one file.

The current web client requests data from `/api/map/*` per interaction, so the
artefact size no longer scales with the dataset.

## Tool history

| Stage | Tool | Why it was dropped |
|---|---|---|
| 1 | Tableau | Custom coordinate mapping was awkward; per-stop dynamic colouring hit a limit |
| 2 | QGIS | Static images only; no stop-click interaction or OD popups |
| 3 | Folium | Static HTML artefact; does not fit server deployment or live filtering |
| 4 | React + Leaflet | Current |

Detail on stages 1–3 is in
[`../legacy_sqlserver/troubleshooting.md`](../legacy_sqlserver/troubleshooting.md),
item 1.

## This script does not run as written

`make_map.py` calls `/api/od/all`, which no longer exists. The current
equivalent lives under `/api/curated-od/*`, and the map endpoints under
`/api/map/*` were added afterwards. See
[`docs/architecture/map_demand_api.md`](../../architecture/map_demand_api.md).

It is kept as a record, not as a tool to revive.