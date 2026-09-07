# Terrain API — revised integration package

This is a replacement for the original seven-file ZIP, not a patch to the already
running application. It retains the two original endpoint paths and adds metadata
and offset pagination. The terrain domain is read-only. A separate publisher owns
its data lifecycle. Existing demand queries and source tables are not modified.

## What changed, and why

| Review finding | Implementation |
|---|---|
| Master reload destroys derived columns | `bus_stop_terrain_stats` is a versioned, independent snapshot with its own coordinates and labels. No runtime master JOIN is required. |
| Multiple boundary dates / polygon matches | Explicit `YYYYMMDD` source date, same-code polygon dissolution, one stored assignment per stop. Shared-boundary ties choose the lowest code and record ambiguity. Multiple containing interiors reject publication. |
| NULL silently changes the denominator | `totalStops`, `measuredStops`, `missingSlopeStops`, `steepStops`; ratio uses measured stops and is null if none are measured. |
| Missing elevation becomes zero | Nullable row and response fields, including aggregate elevation. |
| Cached results survive a new import | Resolve the active dataset on every request; cache key contains the immutable dataset ID and query parameters. Existing Redis/Caffeine TTL remains in force. |
| Invalid requests / arbitrary 100% ceiling | Finite nonnegative grade, 1–1000 limit, nonnegative offset and minStops; scoped HTTP 400/503 handling. Grades above 100% are valid. |
| Unstable order / no pagination | Grade descending, exact node ID ascending; `limit` and `offset`. |
| Missing map geometry | Longitude/latitude included in stop responses. |
| No regression tests | Service, HTTP, cache, PostGIS repository and optional full publisher integration tests. |
| Accessibility overclaim | Terrain screening terminology; no claim of measured pavement or wheelchair accessibility. |

The public JSON fields previously returned by the original DTOs remain, but
`totalStops` now means all assigned stops, not just measured ones. New fields
make the denominator explicit. This is an intentional semantic correction.

## 1. Installation

From the SeoulTransitPlatform repository root in PowerShell, extract the
ZIP directly into the repository root. The archive has no enclosing folder.
The Java package is already under the correct Maven source directory.

```powershell
cd C:\Users\miyum\SeoulTransitPlatform
Expand-Archive -LiteralPath "C:\path\to\terrain-api-reviewed-project-layout.zip" -DestinationPath . -Force
```

**Check the destination before running migrations:**

```powershell
Test-Path .\services\api-server\src\main\java\com\ian\transit\terrain\api\TerrainAccessibilityController.java
Test-Path .\services\api-server\src\test\java\com\ian\transit\terrain\TerrainQueryRepositoryIntegrationTest.java
```

The ZIP includes all reviewed Java source and test classes, the separate
publisher, migration scripts and an isolated verification POM. The source and
test dependencies are already present in the repository's Java 21 / Spring Boot
4.0.5 POM as checked on 2026-09-07. No new Maven dependency is required.
The isolated POM is at `tools/terrain-verification/pom.xml`; it compiles the
project-layout source and tests without changing the existing backend POM.

**Cache configuration:** apply `patches/terrain-cache.patch` from the repository
root, or add `terrainDongSummary` to the existing `spring.cache.cache-names`
list in `services/api-server/src/main/resources/application.yaml`. Preserve the
other cache names and the existing Redis/Caffeine configuration. The patch does
not replace the default CacheManager. The existing `CacheConfig` already enables
caching. Redis is the deployment default and Caffeine is the local-simple option.

```powershell
git apply --check .\patches\terrain-cache.patch
git apply .\patches\terrain-cache.patch
```

If the patch does not apply because the local file differs, edit only the
cache-names line manually. Do not overwrite the whole application.yaml.

## 2. Database migration and provenance

Back up the database first. Run the additive migration once, using an account
that can create the four new tables and PostGIS extension. **It does not alter,
DROP, TRUNCATE or replace `bus_stop_location`, the demand tables, or
`admin_dong_boundary`.** There is no automatic schema creation on application
startup. The migration contains uniqueness/FK constraints and guards against
editing published snapshots. It can be rerun, but it is not a migration system
for an incompatible pre-existing table with the same name.

```powershell
$env:PGPASSWORD = $env:DB_PASSWORD
psql -X -v ON_ERROR_STOP=1 -h localhost -U postgres -d Seoul_Transit -f .\database\terrain\sql\01_terrain_schema.sql
psql -X -v ON_ERROR_STOP=1 -h localhost -U postgres -d Seoul_Transit -f .\database\terrain\sql\02_legacy_source_check.sql
```

The new tables are `terrain_dataset`, `bus_stop_terrain_stats`,
`terrain_stop_dong_assignment`, and `terrain_active_dataset`. One dataset ID
identifies a complete, immutable snapshot. The active pointer changes in one
transaction. Publication fails if counts disagree, a stop has more than one
assignment, or an existing dataset ID is reused. A SQL administrator can still
bypass ordinary row triggers with TRUNCATE; application credentials should have
read-only access to these tables, while a separate publisher role receives only
necessary write permissions. Do not grant application users schema-owner rights.

The actual repository schema identifies the master key as `"노드id"` and the
boundary date as `base_date VARCHAR` containing `YYYYMMDD`. The documented
boundary code is eight digits. The publisher uses exact source IDs and does
**not** LPAD or infer a mapping to the demand platform's canonical IDs. If a
CSV uses another ID namespace, reconcile it explicitly before publishing.

### Source input

The preferred source is a canonical CSV exported by geo-raster-pipeline:

```text
node_id,stop_no,stop_name,longitude,latitude,elev_m,slope_pct
00001,001,Example,127.0,37.0,25.0,8.0
```

Coordinates must be EPSG:4326 longitude/latitude. `slope_pct` is percentage
grade, not degrees. Empty measurement fields are NULL; `-9999` is treated as
NoData by default. The publisher does not recompute or correct slopes: supply
the corrected output, and record the actual method used. If the existing CSV
uses different headers, pass a JSON `--csv-map` mapping canonical names to
actual names. All seven canonical fields are required after mapping. There is
no guessed conversion from an unknown source schema.

For the current database, the compatibility option `--from-master` reads the
original master's `elev_m`, `slope_pct`, `geom`, and Korean identifier/name
columns. This requires those derived columns to already exist. It is intended
for the current result, not as the long-term pipeline interface. The importer
copies the rows before publication and never writes back to the master.

### Publish safely

Install the Python dependency in the pipeline's environment:

```powershell
python -m pip install -r .\pipelines\terrain\requirements.txt
$env:SEOUL_TRANSIT_DB = "postgresql+psycopg2://user:password@localhost:5432/Seoul_Transit"
```

Use your actual boundary date from the diagnostic query and a unique version.
The strings below are examples, not verified values for your local DEM run.

```powershell
python .\pipelines\terrain\publish_terrain.py --from-master `
  --dataset-id "glo30-seoul-v1" --source-id "Copernicus GLO-30" `
  --slope-method "verified-pipeline-method" --boundary-date "YYYYMMDD" `
  --expected-stops 11480 --dry-run
```

Replace `YYYYMMDD` with a real date (for example, `20260101` only if that is
what your boundary data contains). Inspect the report. To publish, remove
`--dry-run`; to make it live immediately, also add `--activate`. Publication
without activation leaves the existing API dataset unchanged. With a canonical
CSV, replace `--from-master` with `--csv "C:\path\to\stop_terrain.csv"`.
Optional `--expected-at-least` checks the count at **>=8%**, but do not set it
to 4,536 until the projection correction and source result are verified.

For a corrected or refreshed dataset, use a new ID. A failed import rolls back
all new rows and preserves the active pointer. The publisher reports unmatched
and ambiguous-boundary counts. Multiple containing interiors reject the import;
shared-boundary ties choose the lowest `adm_cd` using C collation. A separate
boundary source date is required, and identical-code fragments are dissolved
before assignment. Every source stop receives exactly one assignment row,
including unmatched stops. Source coordinates and names are snapshotted, so a
master reload does not change the published analysis; publish a new version if
you want updated stop locations.

Activate an already published version, including for rollback:

```powershell
psql -X -v ON_ERROR_STOP=1 -h localhost -U postgres -d Seoul_Transit `
  -v dataset_id="glo30-seoul-v1" -f .\database\terrain\sql\04_activate_dataset.sql
```

The SQL script uses an advisory lock shared with the publisher and rejects an
unpublished version. A missing active dataset returns HTTP 503, not an empty
statistical result. Do not run the publisher against production until the
source schema, date and method have been checked.

## 3. API contract

`GET /api/stops/accessibility?minSlope=8&limit=100&offset=0`

Returns measured stops at or above the **inclusive** percentage-grade threshold,
sorted by grade descending and exact node ID ascending. `limit` is 1–1000;
`offset` is 0–1,000,000. It returns coordinates, elevation (nullable),
administrative code/name (nullable), assignment method/candidate count, and
dataset version/date. The optional offset is a minimum change to the existing
endpoint. For large map views, a future bbox/keyset API would be more efficient.

`GET /api/stops/accessibility/summary?minSlope=8&minStops=10`

Returns assigned administrative dongs, ordered by steep ratio descending,
measured count descending, and code ascending. `minStops` is the minimum
**measured** count; zero is permitted. `totalStops` includes missing-slope
stops; `measuredStops` is the ratio denominator; `missingSlopeStops` exposes
coverage. `steepRatioPct` is NULL when measuredStops is zero. A dong with no
assigned stops does not appear. Unmatched stops appear in the list but are not
silently assigned to a dong. No overlap is double-counted across dongs.

`GET /api/stops/accessibility/metadata`

Returns dataset version, source, method, boundary date, processing/publication/
activation timestamps and source/measurement/assignment counts. Empty list
responses do not carry metadata, so clients should use this endpoint for
provenance and freshness. A response is consistent with its stated dataset
version even if the active pointer changes during a concurrent request.

Invalid numeric inputs return a scoped 400 JSON error. No active published
snapshot returns a scoped 503 error. Database/configuration failures are not
silently translated into successful empty results.

**Interpretation:** 8% is a configurable screening threshold, not an automatic
ADA compliance decision. GLO-30 is a DSM and can include buildings/vegetation.
A 30m-cell terrain gradient is not the gradient of the stop's pavement or its
accessible route. This API does not certify wheelchair accessibility. The
previous 39.5% figure is not asserted as a measured accessibility rate or a
mathematically guaranteed upper bound. Use a verified bare-earth DTM and route-
level measurements before making those claims.

## 4. Tests

The ZIP includes an isolated Maven verification project and tests already placed in the backend test source tree.
The Java integration test uses disposable PostGIS 16 / PostGIS 3.4 via
Testcontainers. Docker Desktop must be running for the DB tests.

```powershell
cd C:\Users\miyum\SeoulTransitPlatform
mvn -f .\tools\terrain-verification\pom.xml test
```

Run the actual backend test suite:

```powershell
cd C:\Users\miyum\SeoulTransitPlatform\services\api-server
mvn test
```

The Java tests cover input validation, nullable aggregates, inclusive threshold,
versioned cache keys, HTTP 400/503, real SQL aggregation, pagination, snapshot
isolation and immutable-row guards. `services/api-server/src/test/resources/terrain-schema.sql` is an
exact copy of the shipped migration for the disposable DB test. If you modify
the migration, update that test resource as well.

Python unit tests can run without a DB. The optional publisher integration tests
start a disposable PostGIS container and exercise actual publication, rollback,
boundary ties, overlap rejection and master-reload independence.

```powershell
python -m pip install -r .\pipelines\terrain\requirements-test.txt
python -m pytest -q -c .\pipelines\terrain\tests\pytest.ini .\pipelines\terrain\tests
$env:RUN_TERRAIN_INTEGRATION = "1"
python -m pytest -q -c .\pipelines\terrain\tests\pytest.ini .\pipelines\terrain\tests
```

The second command requires Docker. The optional integration fixture creates a
new disposable database for each test; it never connects to your production DB.
The source archive contains no production DEM, database credentials or datasets.
The publisher owns its transaction and requires an idle connection if imported as a Python function; commit or roll back any caller transaction before invoking it.

## 5. Review explanation

**Why a separate domain?** The demand queries have their own cardinality and
performance guarantees. A terrain read model has a different refresh cadence
and should not make a tested demand query depend on a new spatial join.

**Why snapshot the join?** A many-to-many point/polygon join is not a safe unit
of counting. Pinning the boundary date and storing one assignment per stop makes
the denominator reproducible and the runtime query cheap. Boundary ambiguity is
reported instead of silently disappearing.

**Why separate master and derived data?** Master refreshes and DEM processing
have different lifecycles. Immutable versions make a failed refresh and rollback
safe, preserve provenance, and prevent stale cached aggregates after activation.

**Why JdbcTemplate?** The existing backend is SQL-oriented. Explicit PostGIS
and PostgreSQL filtered aggregates make cardinality and NULL semantics visible;
JPA is not incapable of these queries, but adds no benefit here.

**Why test the database?** The most dangerous errors are plausible-but-wrong
counts, not exceptions. Synthetic boundary, NULL and duplicate fixtures protect
the analytical contract. This patch has not been validated against the user's
live database, and the original 4,536-stop result is not a test oracle until its
measurement method is corrected.

## 6. Remaining operational decisions

The exact geo-raster-pipeline CSV schema and corrected slope method are not in
the original API ZIP. The canonical CSV interface avoids inventing them; the
actual pipeline exporter still needs to target that interface. This package
does not add a cloud deployment, a scheduler, or an authenticated write API.
The publisher is an operator-run command. Production permissions, backups,
actual source counts, boundary-date selection and deployment integration must
be checked in the target environment. No existing demand source is migrated or
rewritten by this ZIP.