# Publishing a terrain snapshot

> Moved from the project README.

## Snapshot tables

| Table | Contents |
|---|---|
| `terrain_dataset` | One row per snapshot: source DEM, slope method, boundary base date, per-category counts |
| `bus_stop_terrain_stats` | Elevation and slope per stop, per dataset |
| `terrain_stop_dong_assignment` | Exactly one dong per stop, per dataset, with the method used |
| `terrain_active_dataset` | Single-row pointer to the snapshot currently served |

## Publishing

```powershell
$env:SEOUL_TRANSIT_DB='postgresql+psycopg2://postgres:...@localhost:5432/Seoul_Transit'

python pipelines/terrain/publish_terrain.py --csv <geo-raster-pipeline output> --dry-run `
    --dataset-id glo30-20260907 `
    --source-id copernicus-glo30 `
    --slope-method "gdaldem slope -p -s 0.7934" `
    --boundary-date 20250630 `
    --expected-stops 11480 --expected-at-least 4536
```

`--dry-run` rolls back, so it exercises staging, boundary verification and dong
assignment without writing. `--expected-stops` and `--expected-at-least` are
optional guards: publication fails unless the source reproduces those counts.

Then `database/terrain/sql/03_terrain_diagnostics.sql` to inspect, and
`04_activate_dataset.sql` to make the snapshot live.

Source data: Copernicus WorldDEM™-30 © DLR e.V. 2010–2014 and © Airbus Defence
and Space GmbH 2014–2018, under COPERNICUS by the European Union and ESA.
Administrative boundaries: `admin_dong_boundary`, base date 20250630.
