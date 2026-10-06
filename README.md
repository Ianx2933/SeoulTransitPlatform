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

## Live demo

**[seoul-transit-prod.web.app](https://seoul-transit-prod.web.app)** — the map
client, served from Firebase Hosting against the deployed API.

The backend scales to zero, so the first request after an idle period waits for
a cold start. The OD correction endpoints are deliberately locked in the
deployed environment: they mutate data, and `ADMIN_API_TOKEN` is left unset so
they reject every request. Their contracts are still visible in the OpenAPI
document.

See [deployed architecture](#deployed-architecture) for what runs where.

## Highlights

| | |
|---|---|
| **Query performance** | District-demand endpoint reduced from ~10 s to 192 ms cold — [analysis](docs/performance/phase6_9_2_district_demand_optimization.md) |
| **Data correction** | Four-stage OD identifier recovery plus a separate three-stage coordinate matcher with per-row method/confidence provenance |
| **Spatial stack** | PostGIS with GIST, composite, and expression indexes over 1,208 administrative boundaries |
| **Raster integration** | Terrain attributes from a Copernicus GLO-30 DEM published as immutable versioned snapshots, served per stop and per dong |
| **Demand profiling** | Hourly boarding demand loaded as a labelled 3-D array and grouped by terrain band, reconciled against the SQL source |
| **Analytical warehouse** | The analytical half migrated to BigQuery as a partitioned star schema, reconciled row-for-row; serving stays in PostGIS — [transit-bigquery](https://github.com/Ianx2933/transit-bigquery) |
| **Data validation** | Reconciliation checks found a loader overwrite understating monthly boarding by 3.6% and holiday-table errors in six months; fixed, rebuilt on 32 months, and now asserted by tests that surfaced two further holiday omissions — [details](#data-quality-rebuilding-the-hourly-pipeline) |
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
| Hourly pipeline rebuild | Done | Loader and ratio-model fixes, rebuilt on 2024-01 to 2026-08 and validated |
| Phase 7 | Done | GCP deployment — serving path and scheduled ingestion both live |

## Key capabilities

- Node-centered demand lookup around a coordinate radius.
- District-centered demand aggregation by administrative dong.
- Terrain screening by stop and by administrative dong, from published DEM
  snapshots.
- Bus and subway mode filtering.
- Weekday/day-type and hour filtering.
- Route, node, and district-level demand summaries.
- Redis cache by default locally; Caffeine in the deployed environment.
- Caffeine cache through `local-simple` profile for lightweight local execution.
- PostgreSQL/PostGIS spatial lookup and performance indexes.

## Tech stack

| Layer | Technology |
|---|---|
| Backend API | Spring Boot, Java 21, Maven |
| Database | PostgreSQL, PostGIS |
| Cache | Redis, Caffeine |
| Prediction service | Python, Flask, XGBoost |
| Frontend | React, Vite, Leaflet (OpenStreetMap basemap) |
| Pipelines | Python, Airflow |
| Multidimensional analysis | xarray, dask, netCDF |
| Raster processing | GDAL/OGR, rasterio (external — [geo-raster-pipeline](https://github.com/Ianx2933/geo-raster-pipeline)) |
| Analytical warehouse | BigQuery (external — [transit-bigquery](https://github.com/Ianx2933/transit-bigquery)) |
| Local infra | Docker Compose |
| Deployment | Cloud Run, Cloud SQL (PostgreSQL 18 + PostGIS), Artifact Registry, Secret Manager, Firebase Hosting |
| Testing | pytest, JUnit 5, Testcontainers/PostGIS, Vitest, GitHub Actions |

## Deployed architecture

Everything runs in `asia-northeast3` (Seoul).

```text
Firebase Hosting ──rewrite /api/**──> Cloud Run: transit-api (public)
  static Vite build                     │
                                        ├──unix socket──> Cloud SQL: PostgreSQL 18 + PostGIS
                                        │                   ▲
                                        │                   │ unix socket
                                        │                   │
                                        │   Cloud Scheduler ──> Cloud Run Jobs: transit-collector
                                        │     monthly              └──> Seoul Open API
                                        │
                                        └──ID token─────> Cloud Run: transit-prediction (private)
```

### Decisions worth explaining

**No Memorystore.** The application defaults to Redis, but the deployed service
runs Caffeine (`CACHE_TYPE=caffeine`). Memorystore's smallest instance costs
roughly three times the database, which is the wrong trade for a demo whose
only fixed cost is a `db-f1-micro`. Each Cloud Run instance then holds its own
cache; acceptable at this traffic, and the cache provider is a single
environment variable away from changing.

**The prediction service is not public.** It is deployed with
`--no-allow-unauthenticated`, and `transit-api` attaches a Google-signed ID
token whose audience is the service URL. Model inference is the kind of
endpoint that costs money per call, so it should not be reachable by anyone who
finds the URL. The token path is disabled locally
(`PREDICTION_SERVICE_AUTH_ENABLED=false`) because no application default
credentials exist there.

**No CORS configuration.** Firebase Hosting rewrites `/api/**` to the Cloud Run
service, so the browser only ever sees one origin. The frontend calls relative
paths and needs no build-time API URL.

**Pipeline intermediates stay out of the cloud database.** Five tables holding
OD estimates and day-of-week ratio models are transform outputs the API never
reads, so they were left behind; loading only what the serving path queries
keeps the instance inside its 10 GB disk. The hourly passenger table is the
exception — it was created empty in Cloud SQL rather than copied, because the
scheduled job rebuilds it a month at a time from the source API.

**Browser-facing API without a hosted basemap key.** The map originally used
CartoDB Positron, whose CDN began returning watermark tiles for
unauthenticated requests. The client moved to OpenStreetMap rather than
embedding a CARTO key in a public static bundle.

**Cloud Scheduler rather than managed Airflow.** Only one of the ten pipeline
modules calls an external API, and it is a single step with no dependencies —
Cloud Run Jobs on a monthly trigger covers it. Airflow earns its keep on the
local DAG, where ingestion, OD correction, hourly estimation and aggregation
depend on each other in order. Cloud Composer, the managed option, starts
around thirty times the monthly cost of the database it would be feeding.

**The scheduled job computes its own month.** Cloud Scheduler sends no
arguments, and writing the month into the trigger would mean editing the
trigger every month. The loader defaults to the previous month when called
with no arguments; because the upsert is idempotent, a month that was already
loaded is simply rewritten with the same values.

### Environment variables added for deployment

| Variable | Default | Why it exists |
|---|---|---|
| `PORT` | 8080 | Cloud Run assigns the port; `server.port` must read it rather than bind a fixed one |
| `DB_POOL_MAX` | 5 | Hikari's default of 10, multiplied by Cloud Run instances, exceeds what `db-f1-micro` accepts |
| `REDIS_HEALTH_ENABLED` | true | Actuator registers a Redis health check from the starter being on the classpath, not from the active cache provider. Where no Redis exists the check fails permanently and `/actuator/health` answers 503 |
| `PREDICTION_SERVICE_AUTH_ENABLED` | false | Attach an ID token to prediction calls |

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

Switching `CACHE_TYPE` alone does not silence it. Actuator registers the Redis
health check because `spring-boot-starter-data-redis` is on the classpath, not
because Redis is the active cache, so an environment with no Redis at all needs
`REDIS_HEALTH_ENABLED=false` as well. The deployed service sets both.

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

The hourly suite covers the three defects above as contracts rather than as
history: profile precedence and passenger conservation along each of the three
selection paths, page-boundary merging in the loader, and the holiday calendar
against fixed-date and lunar-calendar sources independent of the committed CSV.
These run offline on synthetic fixtures. Checks that assert the state of real
data live in `pipelines/hourly_od_estimation/validate_rebuild.py`, which needs a
populated database and is run after a rebuild rather than in CI.

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

## Data quality: rebuilding the hourly pipeline

Extending the hourly boarding table from six months (2025-01 to 2025-06) to
thirty-two (2024-01 to 2026-08) surfaced four defects that had been producing
plausible numbers. None raised an error. Each was found by reconciling a figure
against something independent of the pipeline that produced it.

**The loader overwrote passengers.** The table key is
`(use_ym, route_no, stop_ars, hour)`, but the source API returns one row per
stop *visit*. A route that passes the same stop twice — near its origin and
again at its turnaround — arrives as two rows with the same stop ID and ARS code
and different sequence numbers, and the upsert kept only one of them. On route
동대문01 at 회기역 the two rows for 2024-01 carried 97,271 and 3,389 boardings;
the table held one of those figures instead of their sum. Every month 3,400–3,900
of roughly 42,000 source rows collided this way, and 2024-01 boarding was
understated by 4,518,304 (3.6%). Row counts were unaffected, so a row-for-row
reconciliation would not have caught it.

Two checks ruled out the other explanation, that the API was sending duplicates:
none of the 3,328 colliding groups had identical values, and every group shared a
single stop ID. The loader now sums a whole month before writing — summing per API
page is not enough, because a collision can straddle a page boundary — and the
upsert still assigns rather than adds, so re-running a month stays idempotent.

**The holiday table had wrong dates.** Day-type counts per month are the design
matrix of the ratio model, so they were checked against the calendar: each count,
not just the monthly total. Six months failed. 2024 Lunar New Year was recorded
as January 9–12 instead of February 9–12, the 2024-10-01 temporary holiday was
missing, and the 2025-01-27 temporary holiday was recorded as the 24th. The 2026
rows listed substitute holidays but omitted the holidays that fell on weekends;
two of those were Saturdays, which shifted the June and August counts. A sum
check alone would have passed all six.

Manual review is not a method, so the calendar is now asserted by tests: every
fixed-date holiday in every year the CSV covers, and lunar holidays against
dates computed by a separate calendar implementation rather than the CSV itself.
Those tests immediately found two more omissions the manual pass had missed —
2026-09-26 and 2026-10-03, both Saturdays, the same defect class as the June and
August rows. Both fall outside the loaded range, so no fitted profile changed.

**The fallback ratio did not sum to one.** OD rows with no fitted hourly profile
are spread across the day with a hand-written 24-hour profile. It summed to 1.105,
inflating every fallback estimate by 10.5%. It is now normalised.

**A fitted profile of all zeros dropped passengers silently.** Clipping negative
least-squares estimates to zero leaves 2,327 of 283,353 ratio keys summing to
zero rather than one. Those keys exist, so the estimator preferred them over the
fallback and multiplied the day's passengers by zero: 18,965 of 5,345,649 daily
OD passengers on 2025-11-11 reached no hour at all. Profile selection now has
three levels, and each row records which one applied — a fitted profile when one
sums to one, the normalised fallback as `default_static_ratio_zero_fit` when the
key exists but fitted to zeros, and the same fallback as `default_static_ratio`
when no key exists. The two fallback cases share their numbers but not their
meaning: the first is a route-stop that was observed and could not be decomposed,
the second was never fitted at all, and merging them would hide which population
is growing.

**Virtual stops leaked into the ratio model.** The filter excluded
`stop_ars = '00000'`, but the API marks virtual stops with `~`, which passed
through into 24,696 rows in each derived table. The filter now accepts only
five-digit codes other than `00000`.

The rebuild also changed the model's footing. The solver estimates seven day-type
averages per route-stop-hour from monthly totals. With six months it had at most
six equations for seven unknowns, so every earlier fit was the minimum-norm
solution of an underdetermined system; with thirty-two months the system is
overdetermined. The solver now processes routes in batches and solves all groups
that observed the same months in one least-squares call — equivalent to the
per-group loop, verified against the original on synthetic data — which keeps a
30-million-row input within a machine with 8 GB of RAM. Because the estimation
method is part of the hourly OD table's key, a row moving from the fallback to a
fitted profile would otherwise be kept twice; the estimator now replaces a date
range inside one transaction.

| Check after rebuild | Result |
|---|---|
| Re-loaded months: row counts against the loader log | 24 of 24 match |
| Hourly ratio keys summing to exactly 1 or 0 | 283,353 of 283,353 |
| Non-numeric or `00000` ARS codes in derived tables | 0 |
| Duplicate OD-hour rows across estimation methods | 0 |
| OD keys in the estimate against the source | 514,499; 0 missing, 0 extra |
| Hours per OD key | 24 distinct, no exceptions |
| Daily passengers conserved per OD key | 0 mismatches at 1e-07 |
| Daily OD passengers not distributed to hours | 0 of 5,345,649 |
| Fallback-profile rows, 2025-11-11 | 243,960 — 181,272 with no key, 62,688 fitted to zeros |
| Estimator re-run: row count and SHA-256 over every row | identical |

Fallback rows rose from 181,272 because 2,612 OD keys that previously lost their
passengers to a zero profile are now distributed; the figure to read alongside it
is the zero in the row above.

The totals are reconciled per OD key rather than in aggregate. A per-key
tolerance of 1e-07 passengers catches errors that cancel out in a 5.3-million
sum, and missing keys, extra keys and missing hours are separate checks rather
than a difference in one total. `validate_rebuild.py` runs all of them and exits
non-zero, so a rebuild that printed success but left the data wrong fails the
gate instead of the reader.

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

**Result.** Mean boardings per stop-hour, 2025-01 to 2025-06, by slope band.
These figures predate the [loader fix](#data-quality-rebuilding-the-hourly-pipeline) and will shift when re-run;
stops visited twice per trip were the most understated.

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
firebase.json           Hosting config and the /api/** rewrite to Cloud Run
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

In the deployed environment, `DB_PASSWORD` and `SEOUL_BUS_API_KEY` come from
Secret Manager and are mounted as environment variables by Cloud Run; the
runtime service account is granted `secretAccessor` on each secret
individually rather than at project scope.

`ADMIN_API_TOKEN` is deliberately left unset in deployment. The
`/api/od-correction/**` endpoints issue UPDATE and DELETE statements against
the OD table, so on a public demo they reject every request rather than
depending on a token staying secret.

## Known gaps

Tracked here so they are visible rather than discovered during deployment.

1. `admin_dong_boundary` has no loader script. The table holds the 1,208
   administrative units of the 2025-06-30 snapshot, imported manually with
   ogr2ogr and documented in
   `database/schema/04_reference_admin_dong_boundary.sql`, so the data is
   present but the import is not reproducible. Terrain publication depends on
   it, and validates its base date and geometry before proceeding.
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
6. Outputs derived from the hourly table before the rebuild have not been
   regenerated: the BigQuery warehouse load and the xarray terrain profile above.
   Both predate the loader fix, so their totals are understated.
7. Temporary, election and substitute holidays are not asserted against an
   external source. Fixed-date and lunar holidays are, but a one-off holiday
   that is simply absent from the CSV would still pass unnoticed.
8. The deployed schema differs from the local one. `pg_dump -t` exports tables
   and their indexes and constraints but not functions, so
   `terrain_guard_activation()` and `terrain_guard_snapshot()` were not carried
   over and the four terrain guard triggers do not exist in Cloud SQL. The API
   only reads those tables, so serving is unaffected — but any attempt to run
   the terrain publisher against the cloud database would bypass the guards.
9. Cloud Run services run as the default Compute Engine service account, which
   carries `roles/editor`. A dedicated service account with only
   `cloudsql.client`, `secretAccessor` and `run.invoker` would be the correct
   scope.
10. The Seoul Open API takes its key as a path segment, so a request URL
    contains the key verbatim. A connection timeout inside `requests` puts that
    URL into the exception message, which then reaches Cloud Logging. The key
    needs rotating, and `fetch_page` should re-raise with the key redacted.
11. Two separate Seoul Open API keys are in use — one for the bus master data
    the API server reads, one for the hourly ridership the loader fetches.
    Using the wrong one does not fail: the service answers `INFO-000` with an
    empty result set, so the loader reports zero rows and exits successfully.
    That cost an hour of looking in the wrong place.

## Next work

1. Move the remaining pipeline modules behind Airflow. Only the correction DAG
   is migrated so far; the other eight still run by hand. The hourly loader is
   now scheduled in the cloud, but that covers ingestion into the serving
   database, not the transform chain that feeds it.
2. Carry the terrain guard functions into Cloud SQL, or decide deliberately
   that the cloud database stays read-only for pipeline writes. The current
   state is neither — the triggers are absent because of how the schema was
   exported, not because anyone chose that.
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
