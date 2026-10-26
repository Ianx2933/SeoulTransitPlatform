# SeoulTransitPlatform — Engineering Decision Log

> As of October 2026 · Records the **context, implementation, and trade-offs** behind technology choices and changes.  
> ADR numbers reflect documentation order, **not** the chronological dates of adoption.

## ADR-01 · Separation of Python, Java, and JavaScript responsibilities

**Context:** From the beginning, the project needed data processing, a service backend, and a browser frontend. All three languages were considered at the outset.  
**Decision:** Use Python for familiar data-processing and analytics workflows; Java for server applications and a mature backend ecosystem; and JavaScript for browser suitability and existing experience. Use SQL for relational and spatial queries, transformation, and aggregation.  
**Current position:** Preserve the functional boundaries while checking that Java and Python do not duplicate the same analytical logic.

## ADR-02 · Java 17 → Java 21

**Context:** I/O-heavy API requests wait on external APIs and databases; concurrency efficiency and long-term support mattered.  
**Decision:** Upgrade to Java 21 for finalized Virtual Threads support and a later LTS support horizon.  
**Trade-off:** Virtual Threads help manage many waiting tasks, not the execution time of an individual slow SQL query.  
**Current position:** Retain Java 21. Actual concurrency configuration and measurement are tracked in the [Roadmap](ROADMAP.md).

## ADR-03 · Microsoft SQL Server → PostgreSQL/PostGIS

**Context:** Spatial indexes had been designed on SQL Server, but the then-current spatial query predicates and structure prevented effective index use. Spatial SQL and GIS processing were central requirements.  
**Decision:** Migrate to PostgreSQL/PostGIS **while redesigning the SQL queries and geospatial processing structure**, rather than performing a direct database swap. Optimize the related queries.  
**Observed outcome:** After migration and redesign, the system was less dependent on caching, making Caffeine a practical option in the cloud deployment.  
**Engineering distinction:** SQL Server does support spatial indexes; the issue concerned how the project's queries used them. The DBMS migration and query/data-model redesign were carried out together.  
**Current position:** Retain PostGIS for service-side spatial analysis and queries.

## ADR-04 · Query indexing first, then caching: Redis locally → Caffeine in GCP

**Context:** The `district-demand` endpoint took roughly **9.5–12.9 seconds** locally. Profiling found spatial joins running without GIST support and identifier comparisons normalised with `LPAD`, which no plain btree index could serve.
**Decision:** Add spatial (GIST), composite, and expression indexes and refresh table statistics, rather than adding a precomputed node-to-district mapping table that would need rebuilding whenever stops or boundaries change.
**Measurement:** After indexing, a measured call took **490 ms**; with the cache flushed, cold calls stayed **under one second**, with a **best observed cold call of about 192 ms**. These are local single-machine measurements; 192 ms is a best observation, not a p50/p95.
**Caching:** Redis caches repeated requests in local execution. Because the indexed database path is already sub-second without a cache hit, the cloud application uses an in-process **Caffeine** cache instead of a shared Redis instance, whose smallest managed tier would cost roughly three times the database.
**Trade-off:** Caffeine caches are local to each application instance rather than shared across instances. This is acceptable at current traffic, and the cache provider is configurable.
**Current position:** Keep the index-based query structure and Caffeine in the cloud. Production latency on Cloud Run is tracked in the [Roadmap](ROADMAP.md).

## ADR-05 · BigQuery analytical warehouse

**Context:** The transit-data loading window expanded by **12 months** beyond the initial plan, reaching 32 months and increasing storage and analytical requirements.  
**Decision:** Introduce a separate BigQuery analytical warehouse. Subsequent use showed that wide scans and large aggregations are intrinsic to the platform and align well with BigQuery's analytical design.  
**Responsibility split:** PostgreSQL/PostGIS for service queries and spatial SQL; BigQuery for broader analytics and reusable data-mart models.  
**Current scope:** A foundational star schema and an initial six months of analytical data are loaded. The full refresh and new fact models are in the [Roadmap](ROADMAP.md).  
**Current position:** Retain BigQuery as a dedicated analytics layer.

## ADR-06 · Spring Boot / Flask / React

**Context:** A browser-based service needed to expose existing Python analytics alongside service APIs.  
**Decision:** Use Spring Boot for the Java service backend, Flask for a **Python analytics API called by React/JavaScript**, and React for the browser UI.  
**Benefit:** Python analysis functions can be exposed over HTTP without reimplementing their logic in Java.  
**Current position:** Keep separate API responsibilities while maintaining consistent definitions for shared metrics.

## ADR-07 · React / Vite / Leaflet / map tiles

**Decision:** Use React for the browser UI, Vite for the frontend development server and build process, and Leaflet for displaying and interacting with geospatial analysis results.  
**Change:** The original basemap provider was CARTO. When the CARTO service used by the project became paid, basemap tiles were switched to the official OpenStreetMap server.  
**Observation:** The application has no large-scale map-object rendering requirement and no observed map-interaction performance issue.  
**Current position:** Retain Leaflet for the current workload and comply with OpenStreetMap tile usage and attribution requirements.

## ADR-08 · Airflow orchestration

**Context:** Of the ten pipeline modules, three call external APIs. The local processing chain — ingestion, OD correction, hourly estimation, and aggregation — depends on each step completing in order, so dependencies, retries, failure tracing, selective reruns, and historical reprocessing matter.
**Decision:** Use Airflow for the dependent local chain; the correction DAG is migrated so far and the remaining modules still run by hand. In the cloud, the monthly hourly-ridership loader is a single step with no dependencies, so **Cloud Scheduler + Cloud Run Jobs** runs it instead of managed Airflow.
**Trade-off:** Cloud Composer, the managed Airflow option, would cost roughly thirty times the monthly database cost for a single scheduled step. Future deployment and separation of responsibilities are addressed in the [Roadmap](ROADMAP.md).

## ADR-09 · Parquet / PyArrow / Dask / xarray

- **Parquet:** Efficient storage and repeated reuse of large transit datasets.
- **PyArrow:** Reading and writing Parquet files in Python.
- **Dask:** Partitioned/lazy processing to manage memory pressure from large datasets.
- **xarray:** Multidimensional array analysis with coordinates and dimensions, particularly spatial dimensions.

**Current position:** Keep distinct roles for storage format, file I/O, multidimensional analysis, and large-data execution.

## ADR-10 · GDAL/OGR / Rasterio / Copernicus GLO-30

- **GDAL/OGR:** Conversion and processing of raster and vector geospatial data.
- **Rasterio:** Reading and analyzing raster data from Python.
- **Copernicus GLO-30:** Elevation source initially used for contours/terrain analysis and now also for stop-level elevation and slope attributes.

**Limitation:** GLO-30 can include buildings and vegetation, so derived values must not be presented as surveyed pedestrian-path slopes.

## ADR-11 · Google Cloud Platform and hosting services

**Rationale:** Use a familiar cloud ecosystem and managed/serverless services to keep infrastructure operations manageable while controlling cost. **Budget alerts are configured.**  
**Service selection:** Cloud Run was intended from the outset for serverless container hosting; Cloud SQL hosts PostgreSQL; Firebase Hosting serves the React frontend; Secret Manager stores operational secrets. Cloud Scheduler and Cloud Run Jobs execute scheduled batches.  
**Current position:** Continue with managed and serverless services while monitoring spending, permissions, and secret handling. `.gitignore` prevents new accidental tracking of specified files; it is not a substitute for Secret Manager.

## ADR-12 · Git/GitHub, Docker, GitHub Actions, and tests

**Context:** The project needed version control, a container runtime, and testing across multiple language stacks.  
**Decision:** Use Git/GitHub for version control and Docker for container execution. GitHub Actions automatically runs **pytest, JUnit, Testcontainers, and Vitest** on pushes and pull requests.  
**Current position:** Keep automated tests covering changes to application code and data processing. Distinguish CI (automated checks) from CD (automated deployment).

## ADR-13 · XGBoost forecasting

**Role:** Explore future transit demand through a forecasting model.  
**Design principle:** Forecasts must be labeled separately from observed, corrected, and estimated demand. More complete forecast evaluation and uncertainty reporting are tracked in the [Roadmap](ROADMAP.md).

---

## Decision-record principles

- Keep the **rationale for a choice** separate from **measured outcomes**.
- Distinguish implemented, locally tested, currently operated, and planned work.
- Do not attribute all migration gains to a DBMS switch independently of spatial SQL/index redesign.
- When decisions change, document the follow-up without rewriting the historical rationale.
