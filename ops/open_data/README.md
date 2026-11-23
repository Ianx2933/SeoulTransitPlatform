# Open data bucket

The download page (`#/download`) reads `latest/manifest.json` from the public
bucket `gs://seoul-transit-open-data` and links to the files under
`releases/<window_end>/`. transit-bigquery's `od/open_data_export.py` writes
them at the end of the weekly warehouse run (step `open-data` to run it alone).

| File | Source table |
|---|---|
| `route_stop_od_typical.csv.gz` | `transit_marts.od_typical_route_stop_serving` |
| `district_od_typical.csv.gz` | `transit_marts.fct_od_dong_mode_typical` (with 행정동 codes) |
| `stop_hourly_profile.csv.gz` | `transit_marts.hourly_profile` |
| `stops.csv.gz` | `transit_marts.dim_stop` |
| `typical_accuracy.csv.gz` | `transit_marts.od_typical_accuracy` |

The same five tables are kept in the BigQuery dataset `transit_open`.

## One-time setup

The bucket is in `seoul-transit-prod`, next to the pipeline bucket. Objects are
public (`allUsers` reader); only the warehouse job's service account writes.
CORS lets the site (and `npm run dev`) read the manifest; a custom domain is
added to `cors.json` when it is connected, then the `--cors-file` line is run again.

Downloads are internet egress, billed to the project (sizes are in the
manifest). Watch it in the cost check; a budget alert on the project catches a
download loop.
