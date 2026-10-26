# SeoulTransitPlatform — Engineering Results & Evidence

> As of October 2026 · Documents implemented capabilities and observed measurements.

## 1. Results at a glance

| Area | Implementation / outcome | Scope |
|---|---|---|
| Transit-data rebuild | Rebuilt approximately **30 million hourly boarding/alighting records** for **32 months (Jan 2024–Aug 2026)** | Historical transit-data processing |
| Data error correction | Fixed a per-visit overwrite that understated monthly boarding by 3.6% (2024-01), plus holiday and hourly-allocation errors | Validated per OD key after rebuild |
| OD and spatial analytics | Corrected OD identifiers; analyzed demand by line, stop, and administrative neighborhood | Uses **1,208 neighborhood boundary areas** |
| Database migration | SQL Server → PostgreSQL/PostGIS, **with spatial SQL and processing redesign** | Addressed spatial-index utilization problems |
| Query performance | `district-demand` endpoint: ~9.5–12.9 s → sub-second cold calls (best observed 192 ms) with spatial, composite, and expression indexes | Local measurement; see ER-03 |
| External-facing service | Deployed spatial-query APIs and a React/Leaflet web GIS | **Closed Beta with external users** |
| Terrain analytics | DEM-based stop elevation/slope analysis | Versioned terrain snapshots |
| GCP operations | Cloud Run, Cloud SQL, Firebase Hosting, scheduled monthly collection | Service and batch operations |
| CI | GitHub Actions runs pytest, JUnit, Testcontainers, and Vitest on push/PR | Automated unit and integration test execution |
| Analytics warehouse | Initial BigQuery star-schema/analytical models | Initial six-month load |
| Workflow orchestration | Airflow for the dependent local chain; Cloud Scheduler + Cloud Run Jobs for the monthly loader | Correction DAG migrated; others run by hand |

## 2. Evidence by engineering result

### ER-01 · Rebuilding 32 months of transit data

- **Problem:** Longitudinal transit-demand analysis required checking for missing records, aggregation errors, and hourly allocation defects.
- **Work:** Rebuilt approximately **30 million hourly boarding/alighting records** spanning **32 months**. The source API returns one row per stop visit, and the loader overwrote repeated monthly keys instead of summing them; 2024-01 boarding was understated by **4,518,304 (3.6%)** with row counts unaffected. The loader now sums a whole month before writing. Also corrected holiday dates in six months, a fallback hourly profile that summed to 1.105, and all-zero fitted profiles that dropped passengers.
- **Validation:** After the rebuild, 514,499 OD keys matched the source with 0 missing and 0 extra; daily passengers were conserved per OD key (0 mismatches at 1e-07); 0 of 5,345,649 daily OD passengers were left undistributed; and an estimator re-run produced an identical SHA-256 over every row. `validate_rebuild.py` runs these checks and exits non-zero on failure.
- **Scope:** January 2024–August 2026 (32 months, approximately 30 million records).
- **Significance:** Established a more reliable foundation for analyzing historical transit demand.

### ER-02 · Spatial database migration

- **Problem:** SQL Server spatial indexes had been defined but were not used effectively by the project's then-current spatial queries.
- **Work:** Migrated to PostgreSQL/PostGIS while redesigning spatial SQL, data-processing logic, and related queries.
- **Observed outcome:** Implemented service-side spatial analysis and queries; observed reduced reliance on caching after migration and optimization, enabling the use of Caffeine in the cloud deployment.
- **Significance:** This was a spatial-query and processing redesign, not merely a DBMS replacement.

### ER-03 · District-demand query optimization

- **Problem:** The `district-demand` endpoint took roughly **9.5–12.9 s** locally. Spatial joins ran without GIST support, and `LPAD`-normalised identifier comparisons could not use a plain btree index.
- **Work:** Added GIST, composite, and expression indexes and refreshed table statistics. A precomputed node-to-district mapping table was considered and deliberately not built.
- **Measurement:** After indexing, a measured call took **490 ms**; with the cache flushed, cold calls stayed **under one second**, with a **best observed cold call of about 192 ms**.
- **Measurement scope:** Local single-machine measurements; 192 ms is a best observation, not a p50/p95. Production latency on Cloud Run, which uses Caffeine, has not yet been measured.
- **Consequence:** Because the indexed database path is sub-second without a cache hit, the cloud deployment did not need a shared Redis instance.

### ER-04 · Web GIS with external users

- **Work:** Implemented Spring Boot service APIs, a Flask analytics API in Python, and a React/Leaflet map; deployed the service on GCP and opened it to external beta users.
- **Outcome:** Established a Closed Beta in which people outside the development process can access the map and APIs.

### ER-05 · Multi-stack testing and CI

- **Work:** Configured GitHub Actions to automatically execute Python tests (pytest), Java tests (JUnit), Docker-based integration tests (Testcontainers), and JS/React tests (Vitest) on push/PR events.
- **Outcome:** Changes to the codebase trigger CI testing across multiple application layers, including container-based integration tests.

### ER-06 · Raster analysis and versioned terrain snapshots

- **Work:** Used GDAL/OGR for geospatial conversion, Rasterio/Python for raster analysis, and Copernicus GLO-30 for stop-level elevation/slope attributes with versioned snapshots.
- **Limitation:** The GLO-30 elevation surface and grid resolution cannot precisely represent every actual pedestrian-path gradient. A conservative use case is to flag areas for further accessibility investigation.

## 3. Interpretation and boundaries

- **Service operation:** The web GIS and spatial-query APIs are available in a Closed Beta with external users.
- **Warehouse and orchestration:** BigQuery currently contains an initial six-month load; Airflow DAGs currently run locally. Refreshing the full data history and deploying cloud orchestration are tracked in the [Roadmap](ROADMAP.md).
- **Terrain-data limitation:** Terrain gradients derived from Copernicus GLO-30 are not surveyed measurements of sidewalk or walking-route slope.

See [Engineering Decisions](DECISIONS.md) for the rationale behind these choices, and the [Roadmap](ROADMAP.md) for upcoming work.
