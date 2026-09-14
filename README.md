# SeoulTransitPlatform

[![CI](https://github.com/Ianx2933/SeoulTransitPlatform/actions/workflows/ci.yml/badge.svg)](https://github.com/Ianx2933/SeoulTransitPlatform/actions/workflows/ci.yml)

A geospatial transit demand platform that turns raw smart-card records into
stop-, station-, and district-level demand analysis for the Seoul metropolitan
area.

It exists because the source data does not answer questions directly. Roughly
10M daily transactions arrive with missing route identifiers, inconsistent stop
codes, and incomplete origin-destination records. The platform reconstructs
usable OD flows from that, then serves spatial demand queries on top.

**What it was used to find:** a congested segment that justified a targeted
short-turn bus service, a route segment whose demand pattern argued for
splitting it into a local service, three routes carrying over half their
demand on a single core segment, and — after joining terrain data — a dong where
27 of 29 bus stops sit on ground at or above an 8% grade. See
**[policy insights](docs/analysis/policy_insights.md)**.

![Route 143 congestion analysis](docs/analysis/images/route_143_congestion.png)

## Highlights

| | |
|---|---|
| **Query performance** | District-demand endpoint reduced from ~10 s to 192 ms cold — [analysis](docs/performance/phase6_9_2_district_demand_optimization.md) |
| **Data correction** | Four-stage OD identifier recovery plus a separate three-stage coordinate matcher with per-row method/confidence provenance |
| **Spatial stack** | PostGIS with GIST, composite, and expression indexes over 1,208 administrative boundaries |
| **Raster integration** | Terrain attributes from a Copernicus GLO-30 DEM published as immutable versioned snapshots, served per stop and per dong |
| **Demand profiling** | Hourly boarding demand loaded as a labelled 3-D array and grouped by terrain band, reconciled against the SQL source |
| **Analytical warehouse** | The analytical half migrated to BigQuery as a partitioned star schema, reconciled row-for-row; serving stays in PostGIS — [transit-bigquery](https://github.com/Ianx2933/transit-bigquery) |
| **Reproducibility** | Schema scripts + Docker Compose; automated tests use synthetic fixtures; model binaries excluded by design |

## Current phase status

| Phase | Status | Summary |
|---|---:|---|
| Phase 6.8 | Done | Node-centered map demand analysis and catchment API |
| Phase 6.9 | Done | District-centered all-route demand analysis |
| Phase 6.9-2 | Done | District-demand performance indexing and statistics refresh |
| Phase 6.10 | Done | Deployment readiness — schema scripts, runbooks, profiles, smoke tests, container images |
| Terrain integration | Done | Versioned terrain snapshots and the stop/dong screening API |
| Analytical warehouse | Done | BigQuery star schema for the analytical half — fact plus stop/route dimensions, validated against PostGIS |
| Phase 7 | Planned | GCP deployment architecture and implementation |

## Key capabilities

- Node-centered demand lookup around a coordinate radius.
- District-centered demand aggregation by administrative dong.
- Terrain screening by stop and by administrative dong, from published DEM
  snapshots.
- Bus and subway mode filtering.
- Weekday/day-type and hour filtering.
- Route, node, and district-level demand summaries.
- Redis cache by default for deployment parity.
- Caffeine cache through `local-simple` profile for lightweight local execution.
- PostgreSQL/PostGIS spatial lookup and performance indexes.

## Tech stack

| Layer | Technology |
|---|---|
| Backend API | Spring Boot, Java 21, Maven |
| Database | PostgreSQL, PostGIS |
| Cache | Redis, Caffeine |
| Prediction service | Python, Flask, XGBoost |
| Frontend | React, Vite, Leaflet |
| Pipelines | Python, Airflow |
| Multidimensional analysis | xarray, dask, netCDF |
| Raster processing | GDAL/OGR, rasterio (external — [geo-raster-pipeline](https://github.com/Ianx2933/geo-raster-pipeline)) |
| Analytical warehouse | BigQuery (external — [transit-bigquery](https://github.com/Ianx2933/transit-bigquery)) |
| Local infra | Docker Compose |
| Testing | pytest, JUnit 5, Testcontainers/PostGIS, Vitest, GitHub Actions |

## Terrain screening API

Bus stop demand is served alongside terrain attributes derived from a Copernicus
GLO-30 DEM by the companion project
[geo-raster-pipeline](https://github.com/Ianx2933/geo-raster-pipeline).

```text
GET /api/stops/accessibility?minSlope=8&limit=100&offset=0
GET /api/stops/accessibility/summary?minSlope=8&minStops=10
GET /api/stops/accessibility/metadata
```

The first returns individual stops at or above a slope threshold, steepest
first, with coordinates. The second aggregates by administrative dong, worst
ratio first. The third reports which snapshot is active and how it was produced.

**Result.** Of 11,480 stops, 4,536 (39.5%) sit on terrain at or above an 8%
grade; the median is 6.51%. Per dong the range is wide — Seonghyeon-dong
(성현동) tops the list with 27 of 29 stops (93.1%), while riverside dongs sit
near zero.

### Versioned snapshots, not columns on the master

Terrain values are not stored on `bus_stop_location`. That table is periodically
reloaded from source CSV, and a derived column written there would be destroyed
by the next reload with no error raised — the API would keep returning 200 while
the values quietly emptied out.

Instead each processing run is published as an immutable snapshot:

| Table | Contents |
|---|---|
| `terrain_dataset` | One row per snapshot: source DEM, slope method, boundary base date, per-category counts |
| `bus_stop_terrain_stats` | Elevation and slope per stop, per dataset |
| `terrain_stop_dong_assignment` | Exactly one dong per stop, per dataset, with the method used |
| `terrain_active_dataset` | Single-row pointer to the snapshot currently served |

Publication and activation are separate steps, so a partially loaded snapshot is
never visible. Because snapshots are immutable, the dong-summary cache is keyed
by dataset ID and needs no explicit invalidation.

The stop-to-dong assignment is resolved **once at publication**, not per query.
`ST_Covers` matches a point lying on the shared edge of two polygons, so a
boundary stop would otherwise appear in both dongs and inflate every aggregate.
Interior matches win; remaining ties break deterministically on `adm_cd`; a stop
found strictly inside two polygons aborts the publication, because that means
the boundary layer itself overlaps.

### Publishing a snapshot

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

### What the numbers mean

This measures terrain gradient across the 30 m DEM cell containing each stop.
It is **not** footway gradient and **not** an accessibility assessment. The
source is a surface model that includes buildings, which inflates values in
dense districts; conversely, 30 m resolution averages away short steep pitches.
Treat it as a screening indicator for narrowing down where to survey.

Source data: Copernicus WorldDEM™-30 © DLR e.V. 2010–2014 and © Airbus Defence
and Space GmbH 2014–2018, under COPERNICUS by the European Union and ESA.
Administrative boundaries: `admin_dong_boundary`, base date 20250630.

## Local quick start

All commands run from the repository root unless stated otherwise. Examples use
PowerShell; CMD and bash equivalents are in the
[local runbook](docs/deployment/local_runbook.md).

### 0. Create the database schema

Required on first run. The API server uses `ddl-auto: validate` and will not
start against an empty database.

```bash
psql -h localhost -p 5432 -U postgres -c "CREATE DATABASE \"Seoul_Transit\" ENCODING 'UTF8';"
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/schema/run_all.sql
```

This creates structure only; endpoints return empty results until data is
loaded. Full instructions, including data loading order and Windows-specific
psql setup, are in
[`docs/deployment/database_setup.md`](docs/deployment/database_setup.md).

The terrain tables are created separately, since they are only needed if the
terrain endpoints are used:

```bash
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/terrain/sql/01_terrain_schema.sql
```

### 1. Start infrastructure

```powershell
copy .env.example .env
```

Fill in `DB_PASSWORD` and `ADMIN_API_TOKEN`, then:

```powershell
docker compose up -d
docker exec -it seoul-transit-redis redis-cli ping
```

Expected Redis response:

```text
PONG
```

### 2. Start the API server

```powershell
cd services\api-server

$env:DB_PASSWORD='your-local-postgres-password'
$env:ADMIN_API_TOKEN='local-dev-token'
$env:JPA_DDL_AUTO='validate'
$env:CACHE_TYPE='redis'
$env:REDIS_HOST='localhost'
$env:REDIS_PORT='6379'

mvn spring-boot:run
```

Or run the whole stack in containers after placing the prediction model
artifacts described in `services/prediction-service/README.md`:

```powershell
docker compose --profile app up -d
```

### 3. Run smoke tests

```powershell
curl.exe -i 'http://localhost:8080/actuator/health'

curl.exe -i 'http://localhost:8080/api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8'

curl.exe -i 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50'

curl.exe -i 'http://localhost:8080/api/stops/accessibility/metadata'
```

Expected health result:

```text
HTTP/1.1 200
{"status":"UP"}
```

The terrain endpoints return 503 with a diagnostic body when no snapshot has
been published and activated. That is a missing-data condition, not a failure.

Full request examples and timing interpretation are in
[`docs/deployment/smoke_tests.md`](docs/deployment/smoke_tests.md).

### 4. Start the frontend

```powershell
cd services\web-client

npm.cmd install
npm.cmd run dev
```

Default Vite URL:

```text
http://localhost:5173
```

## Cache profiles

| Profile | Cache | Intended use |
|---|---|---|
| default | Redis | Local Redis and deployment-like execution |
| local-simple | Caffeine | Lightweight local execution without Redis |
| test | Testcontainers Redis/PostGIS | Automated tests |

Run the API without Redis:

```powershell
cd services\api-server

$env:SPRING_PROFILES_ACTIVE='local-simple'
$env:DB_PASSWORD='your-local-postgres-password'
$env:ADMIN_API_TOKEN='local-dev-token'
$env:JPA_DDL_AUTO='validate'

mvn spring-boot:run
```

With the default profile and no Redis running, `/actuator/health` reports
`DOWN`. That is a missing dependency, not a broken build — see
[`docs/architecture/cache_profiles.md`](docs/architecture/cache_profiles.md).

## Tests

The repository treats tests as executable engineering contracts rather than a
coverage-number exercise. The highest-value cases lock down fallback precedence,
ambiguity handling, provenance, record-cardinality preservation, coordinate
validation, prediction distribution totals, API input/error contracts, and
PostGIS boundary semantics.

Python pipeline and prediction-service tests:

```bash
python -m pip install -r requirements-test.txt
pytest -q
```

Backend tests include unit coverage for `OccupancyCalculator` and
`MapDemandService`, MVC-slice 400/404 response contracts, and repository
integration tests. Docker must be available for the shared PostGIS/Redis
Testcontainers context. The PostGIS fixture includes a stop exactly on an
administrative-district boundary and verifies that `ST_Covers` includes it
without changing aggregate totals when `nodeLimit` is applied.

The terrain suite extends the same boundary fixture: it asserts that a stop on a
shared edge is assigned to exactly one dong, that publication is rejected when a
stop falls inside two polygons, that unmeasured stops are excluded from the
ratio denominator rather than counted as flat, and that the row-source
generators stay lazy so publication runs in a single transaction.

```bash
cd services/api-server
./mvnw test
```

Frontend tests:

```bash
cd services/web-client
npm ci
npm test
```

CI runs all three layers on every push to `main` and on pull requests. See
[`docs/engineering/testing_strategy.md`](docs/engineering/testing_strategy.md)
for the rationale and the highest-value invariants.

## Performance result: Phase 6.9-2

The district-demand endpoint was previously measured at roughly 9.5-12.9
seconds on local runs. Profiling identified two causes: spatial joins running
without GIST support, and identifier comparisons normalised with `LPAD` that no
plain btree index could serve.

Adding spatial, composite, and expression indexes and refreshing table
statistics brought cold calls below one second, with a best observed local
result of about 192 ms.

| Baseline | After | Approximate improvement |
|---:|---:|---:|
| 9.5 s | 0.192 s | about 49x |
| 12.9 s | 0.192 s | about 67x |

A precomputed node-to-district mapping table was considered and deliberately
not built — indexing was sufficient, and the table would have needed rebuilding
whenever stop locations or boundaries changed.

The terrain integration made the opposite call and precomputed its stop-to-dong
assignment. The reason is not performance but correctness: a boundary stop
matching two polygons inflates every aggregate, and resolving that per query
would mean re-deriving the same tie-break on every request. Precomputation is
acceptable there because the assignment is scoped to an immutable snapshot, so
"needs rebuilding when boundaries change" becomes "a new snapshot is published",
which is the intended workflow rather than a maintenance burden.

Index SQL: `database/performance/phase6_9_2_district_demand_indexes.sql`
Analysis: [`docs/performance/phase6_9_2_district_demand_optimization.md`](docs/performance/phase6_9_2_district_demand_optimization.md)

## Analytical warehouse: what moved to BigQuery

The analytical half of the platform is migrated to BigQuery as a partitioned,
clustered star schema, reconciled row-for-row against the PostGIS source:
[transit-bigquery](https://github.com/Ianx2933/transit-bigquery).

The serving endpoints stayed here. BigQuery is columnar and scan-priced, with no
concept of a point lookup returning in 200 ms — the 192 ms district-demand budget
above is a serving concern, and moving it would have undone the indexing work it
depends on. So the split is by workload, not by table:

| Stays in PostGIS | Moved to BigQuery |
|---|---|
| District-demand and node-catchment endpoints | Hourly boarding facts |
| `ST_Covers` against dong polygons at query time | Aggregations by route, stop, hour |
| GIST-indexed spatial predicates | Terrain statistics joined to stops |
| Anything with a latency budget | Ad-hoc analyst queries |

Three decisions are documented in that repository.

**No date dimension.** The warehoused source has primary key
`(use_ym, route_no, stop_ars, hour)` — a monthly hourly aggregate with no
day-level detail. A dense date dimension would imply observations that do not
exist.

**Integer partitioning, not date.** Day-partitioned historical data expires on
arrival under the sandbox's non-negotiable 60-day partition expiry, which is
measured from the partition's own date rather than from load time. The load job
reports `DONE`, the table holds zero rows, and no error is raised anywhere. This
is the same class of silent-emptying failure the terrain snapshots were designed
around above.

**Unmatched stops kept, not dropped.** 1,816 of 12,529 stops do not resolve
against the Seoul stop master — Gyeonggi-do stops on cross-boundary routes, plus
route terminus markers. They are carried with a `match_method` column, the same
per-row provenance discipline the four-stage OD recovery already uses here, so
aggregate totals reconcile against the source and analysts exclude them
explicitly rather than inheriting a silently filtered universe.

BigQuery has a native `GEOGRAPHY` type and `ST_DWITHIN` / `ST_DISTANCE` /
`ST_CONTAINS`, but it is not PostGIS — no GIST index control, a different
function set, spherical geometry only. Stop points are loaded as `GEOGRAPHY`
there to demonstrate BigQuery GIS; the spatial serving path stays on PostGIS.

## Terrain and demand: an hourly profile in xarray

The boarding source is already three-dimensional — month, hour, stop — so
`pipelines/analysis/demand_xarray.py` loads it as a labelled array rather than a
flat frame, attaches slope as a non-dimension coordinate on the stop axis, and
groups by terrain band. That turns what would be a `CASE WHEN` pivot into one
`groupby` call, and it is the same reconciliation discipline used elsewhere here:
the array total is asserted against the PostgreSQL sum before anything is read
from it.

Building the full grid is itself informative. 1.79M source rows expand to
6 × 24 × 12,529 cells, and the gaps are real — a stop with no service at 03:00 is
a missing observation, not a zero. Every aggregate uses `skipna` accordingly.

**Result.** Mean boardings per stop-hour, 2025-01 to 2025-06, by slope band:

| Hour | 0–4% | 4–8% | 8–12% | 12%+ | 12%+ as share of 0–4% |
| ---- | ---- | ---- | ----- | ---- | --------------------- |
| 04   | 85.6 | 79.1 | 66.9  | 37.2 | **43%** |
| 05   | 178.9 | 172.5 | 164.2 | 113.8 | 64% |
| 07   | 773.4 | 758.3 | 742.6 | 601.8 | 78% |
| 08   | 963.7 | 951.9 | 960.2 | 756.3 | 78% |
| 18   | 981.4 | 992.6 | 1054.4 | 796.5 | 81% |
| 22   | 451.8 | 490.6 | 521.5 | 379.0 | 84% |

The steepest band carries roughly 78–84% of flat-ground demand through most of the
day. Between 04:00 and 06:00 that gap roughly doubles, reaching 43% at 04:00. In
the evening the 8–12% band exceeds flat ground outright, and its share of the daily
total peaks higher than any other band (0.084 against 0.080 at 18:00).

**This is an observation, not a finding.** Slope is confounded with land use here:
steep districts in Seoul are largely residential and flat ground is largely
commercial, so a residential-versus-commercial hourly profile would produce a
similar shape with no terrain effect at all. Separating the two needs a land-use
control the platform does not currently carry. The figure is recorded because it
is a testable question, not because it answers one — the same standing as the
surface-model caveat on the slope values themselves.

## Documentation

| File | Purpose |
|---|---|
| [`docs/analysis/policy_insights.md`](docs/analysis/policy_insights.md) | Transit policy findings derived from OD analysis |
| [`docs/architecture/overview.md`](docs/architecture/overview.md) | Project background, system design, pipeline, target GCP architecture |
| [`docs/architecture/map_demand_api.md`](docs/architecture/map_demand_api.md) | Map demand endpoint reference |
| [`docs/architecture/cache_profiles.md`](docs/architecture/cache_profiles.md) | Redis/Caffeine/Testcontainers cache profiles |
| [`docs/architecture/target_architecture.md`](docs/architecture/target_architecture.md) | Domain-oriented package refactoring design |
| [`docs/terrain/INSTALL.md`](docs/terrain/INSTALL.md) | Terrain schema setup, publication runbook, migration order |
| [`docs/terrain/TEST_RESULTS.md`](docs/terrain/TEST_RESULTS.md) | What has and has not been verified |
| [`docs/deployment/database_setup.md`](docs/deployment/database_setup.md) | Database creation, schema, and data loading order |
| [`docs/deployment/local_runbook.md`](docs/deployment/local_runbook.md) | Local execution runbook |
| [`docs/deployment/smoke_tests.md`](docs/deployment/smoke_tests.md) | API smoke test commands and expected results |
| [`docs/performance/phase6_9_2_district_demand_optimization.md`](docs/performance/phase6_9_2_district_demand_optimization.md) | 6.9-2 performance analysis |
| [`docs/engineering/testing_strategy.md`](docs/engineering/testing_strategy.md) | Test philosophy, invariants, PostGIS integration tests, and CI |
| [`pipelines/analysis/demand_xarray.py`](pipelines/analysis/demand_xarray.py) | Hourly demand as a labelled array, profiled by terrain band |
| [transit-bigquery](https://github.com/Ianx2933/transit-bigquery) | Warehouse star schema, partitioning, and load validation |
| [`docs/changelog/`](docs/changelog/) | Change records and predecessor project artefacts |

## Repository layout

```text
database/schema/        Schema creation scripts, run in numeric order
database/performance/   Performance index scripts
database/terrain/       Terrain snapshot schema, diagnostics, activation
docs/                   Documentation (see table above)
pipelines/              Python data pipelines by phase
pipelines/analysis/     xarray demand profiling against terrain bands
pipelines/terrain/      Terrain snapshot publisher and its tests
tests/                  pytest contracts for matching and OD correction
.github/workflows/      CI for Python, backend/PostGIS, and frontend tests
pipelines/airflow/      Airflow DAGs
scripts/db/             Data loading helper scripts
services/api-server/    Spring Boot API
services/prediction-service/  Flask prediction service
services/web-client/    React/Vite/Leaflet frontend
```

## Security notes

Do not commit local secrets.

Never commit:

```text
.env
real DB_PASSWORD values
real ADMIN_API_TOKEN values
real SEOUL_BUS_API_KEY values
PostgreSQL passwords
```

Use `.env.example` for placeholders only, and session-level environment
variables for local development.

## Known gaps

Tracked here so they are visible rather than discovered during deployment.

1. `admin_dong_boundary` has no loader script — it is imported manually with
   ogr2ogr, documented in
   `database/schema/04_reference_admin_dong_boundary.sql`. Terrain publication
   depends on it, and validates its base date and geometry before proceeding.
2. `bus_stop_location` and `subway_station_location` have not been compared
   against the live database; the remaining schema files have. See the
   verification table in `docs/deployment/database_setup.md`.
3. Reference CSVs are not committed. Sources and required columns are listed in
   section 4.0 of `docs/deployment/database_setup.md`.
4. Prediction binary model artifacts are intentionally excluded; retraining and
   expected artifact names are documented in `services/prediction-service/README.md`.
5. Terrain snapshots are published manually. There is no scheduled job to
   re-publish when the DEM or the boundary release changes; the active dataset
   has to be replaced deliberately.

## Next work

1. Phase 7 — GCP deployment (Cloud Run, Cloud SQL, Artifact Registry, Secret
   Manager). The container images and compose stack built in Phase 6.10 are the
   input to this. The analytical warehouse is already on GCP; this covers the
   serving path.
2. Schedule the existing hourly loader from Airflow; only the correction DAG is
   migrated so far.
3. Extend OD analysis toward trip-chain analysis — transfer patterns and
   full journey flows rather than single-leg boarding and alighting.
4. Re-publish terrain from a bare-earth DTM (Korea's national 5 m 수치표고모델)
   to remove the building artefacts inherent in the current surface model, and
   compare the two snapshots — the versioning scheme exists to make exactly that
   comparison possible.
5. Extend the warehouse — district dimension, Airflow orchestration of the load,
   and a Type 2 decision for stop renames. The 1,815 Gyeonggi stops carry 2,963
   distinct names, which is the case for a slowly-changing dimension rather than
   the Type 1 currently in place.
