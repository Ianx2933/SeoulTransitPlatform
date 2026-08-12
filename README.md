# SeoulTransitPlatform

SeoulTransitPlatform is a geospatial transit demand platform for analyzing
Seoul public transport activity by stop, station, route, hour, and administrative district.

The project combines a Spring Boot API server, PostgreSQL/PostGIS demand
tables, Redis/Caffeine cache profiles, a Flask prediction service, and a
React/Leaflet web client.

Background, system design, correction strategy, and the target GCP
architecture are in
[`docs/architecture/overview.md`](docs/architecture/overview.md).

## Current phase status

| Phase | Status | Summary |
|---|---:|---|
| Phase 6.8 | Done | Node-centered map demand analysis and catchment API |
| Phase 6.9 | Done | District-centered all-route demand analysis |
| Phase 6.9-2 | Done | District-demand performance indexing and statistics refresh |
| Phase 6.10 | In progress | Deployment readiness, runbooks, profiles, smoke tests |
| Phase 7 | Planned | GCP deployment architecture and implementation |

## Key capabilities

- Node-centered demand lookup around a coordinate radius.
- District-centered demand aggregation by administrative dong.
- Bus and subway mode filtering.
- Weekday/day-type and hour filtering.
- Route, node, and district-level demand summaries.
- Redis cache by default for deployment parity.
- Caffeine cache through `local-simple` profile for lightweight local execution.
- PostgreSQL/PostGIS spatial lookup and performance indexes.

## Tech stack

| Layer | Technology |
|---|---|
| Backend API | Spring Boot, Java, Maven |
| Database | PostgreSQL, PostGIS |
| Cache | Redis, Caffeine |
| Prediction service | Python, Flask |
| Frontend | React, Vite, Leaflet |
| Pipelines | Python, Airflow |
| Local infra | Docker Compose |
| Testing | Maven tests, Testcontainers, Vitest |

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
loaded. Full instructions, including data loading order, are in
[`docs/deployment/database_setup.md`](docs/deployment/database_setup.md).

### 1. Start Redis

```powershell
docker compose up -d redis
docker ps
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

### 3. Run smoke tests

In another window:

```powershell
curl.exe -i 'http://localhost:8080/actuator/health'

curl.exe -i 'http://localhost:8080/api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8'

curl.exe -i 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50'
```

Expected health result:

```text
HTTP/1.1 200
{"status":"UP"}
```

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
| test | Testcontainers Redis/PostgreSQL | Automated tests |

Run the API without Redis:

```powershell
cd services\api-server

$env:SPRING_PROFILES_ACTIVE='local-simple'
$env:DB_PASSWORD='your-local-postgres-password'
$env:ADMIN_API_TOKEN='local-dev-token'
$env:JPA_DDL_AUTO='validate'

mvn spring-boot:run
```

Note that with the default profile and no Redis running,
`/actuator/health` reports `DOWN`. That is a missing dependency, not a broken
build — see
[`docs/architecture/cache_profiles.md`](docs/architecture/cache_profiles.md).

## Performance result: Phase 6.9-2

The district-demand endpoint was previously measured at roughly 9.5-12.9
seconds on local runs. After adding spatial, composite, expression, and
mapping-support indexes and refreshing table statistics, cold calls dropped
below one second, with a best observed local cold result of about 192 ms.

| Baseline | After | Approximate improvement |
|---:|---:|---:|
| 9.5 s | 0.192 s | about 49x |
| 12.9 s | 0.192 s | about 67x |

Index SQL: `database/performance/phase6_9_2_district_demand_indexes.sql`
Analysis: [`docs/performance/phase6_9_2_district_demand_optimization.md`](docs/performance/phase6_9_2_district_demand_optimization.md)

## Documentation

| File | Purpose |
|---|---|
| `docs/architecture/overview.md` | Project background, system design, pipeline, target GCP architecture |
| `docs/architecture/cache_profiles.md` | Redis/Caffeine/Testcontainers cache profile explanation |
| `docs/deployment/database_setup.md` | Database creation, schema, and data loading order |
| `docs/deployment/local_runbook.md` | Local execution runbook |
| `docs/deployment/smoke_tests.md` | API smoke test commands and expected results |
| `docs/performance/phase6_9_2_district_demand_optimization.md` | 6.9-2 performance analysis |
| `docs/changelog/` | Per-patch change records, kept for history |

## Repository layout

```text
database/schema/        Schema creation scripts, run in numeric order
database/performance/   Performance index scripts
docs/                   Documentation (see table above)
infra/                  Deployment configuration (placeholder, Phase 7)
pipelines/              Python/Airflow data pipelines by phase
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

1. No Dockerfile for `services/api-server` or `services/web-client`; only
   `services/prediction-service` has one.
2. `docker-compose.yaml` provides Redis only, not PostgreSQL/PostGIS.
3. `infra/` is a placeholder.
4. `admin_dong_boundary` has no loader script; it is imported manually from the
   SGIS shapefile (documented in `database/schema/04_reference_admin_dong_boundary.sql`).

## Recommended next work

1. Close the gaps above, starting with the two missing Dockerfiles.
2. Decide local-vs-container PostgreSQL strategy.
3. Verify the schema scripts against a clean database on a second machine.
4. Prepare Phase 7 GCP architecture.