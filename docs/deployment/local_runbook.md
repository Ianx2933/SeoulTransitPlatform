# Local Runbook

This runbook describes how to start SeoulTransitPlatform locally for
development, testing, and demo verification.

## Conventions

All paths in this document are relative to the repository root. Open a terminal
there first:

```bash
cd /path/to/SeoulTransitPlatform
```

Commands are given for PowerShell where the syntax differs. Equivalents for
CMD and bash are in the [environment variables](#environment-variables)
section.

## Prerequisites

| Requirement | Version | Notes |
|---|---|---|
| JDK | 21+ | For the API server |
| Maven | 3.9+ | Shell wrapper is included under `services/api-server/mvnw`; Windows can use installed Maven |
| Node.js | 18+ | For the web client |
| Docker | any recent | Compose services and Testcontainers |
| PostgreSQL | 16+ | With PostGIS; Compose pins `postgis/postgis:16-3.4` |

## Required local services

| Service | Required for | Notes |
|---|---|---|
| PostgreSQL/PostGIS | API data queries | Must contain the `Seoul_Transit` schema — see step 0 |
| Redis | Default cache profile | Started by Docker Compose |
| API server | Backend endpoints | Spring Boot/Maven |
| Web client | Map UI | React/Vite |
| Prediction service | Prediction endpoint | Flask; requires generated model artifacts documented in `services/prediction-service/README.md` |

## Window layout

Use separate terminal windows.

| Window | Purpose |
|---|---|
| Window 1 | PostgreSQL/pgAdmin or psql |
| Window 2 | Redis Docker container |
| Window 3 | API server |
| Window 4 | Smoke tests |
| Window 5 | Frontend dev server |

## 0. Database schema

**Do this first on any machine that has not run the project before.**

The API server uses `ddl-auto: validate`. Hibernate validates the schema and
never creates it, so the server will not start against an empty database.

Follow [`database_setup.md`](./database_setup.md), then return here. The short
version, from the repository root:

```bash
psql -h localhost -p 5432 -U postgres -c "CREATE DATABASE \"Seoul_Transit\" ENCODING 'UTF8';"
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/schema/run_all.sql
```

That creates structure only. The server will start and `/actuator/health` will
report `UP`, but map endpoints return empty results until data is loaded —
sections 4 and 5 of `database_setup.md` cover that.

## 0.1 Environment file for Docker Compose

`docker compose` reads `.env` from the directory you run it in. The API server
started directly with Maven does not — it reads the terminal's environment
instead. Both are documented below; they do not conflict.

```cmd
copy .env.example .env
```

Fill in `DB_PASSWORD` and `ADMIN_API_TOKEN`, and update the password inside
`PIPELINE_DB_URL` to match.

Keep `PIPELINE_DB_URL` on one line. Notepad's word wrap makes it look wrapped,
and inserting a real newline produces:

```text
unexpected character "@" in variable name "me@localhost:5432/Seoul_Transit"
```

Turn word wrap off (Format menu) before editing, or rewrite the file:

```cmd
copy .env.example .env
powershell -Command "(Get-Content .env) -replace 'change-me','your-password' | Set-Content .env"
```

Verify:

```cmd
docker compose config > nul && echo compose OK
```

This validates the whole file, including services behind profiles you are not
using.

## 1. PostgreSQL

Start PostgreSQL through Windows Services, pgAdmin, systemd, or your existing
local setup.

Verify the connection:

```powershell
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -c "SELECT 1;"
```

If `psql` is not on PATH, call it by full path. For example on Windows:

```powershell
& "C:\Program Files\PostgreSQL\13\bin\psql.exe" -h localhost -p 5432 -U postgres -d Seoul_Transit -c "SELECT 1;"
```

Expected result:

```text
 ?column?
----------
        1
```

Confirm the schema is present:

```powershell
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -c "\dt public.*"
```

If `analysis_table_final` is missing, go back to step 0.

## 2. Redis

From the repository root:

```powershell
docker compose up -d redis
docker ps
docker exec -it seoul-transit-redis redis-cli ping
```

Expected response:

```text
PONG
```

If Docker fails with a Docker API or named pipe error, start Docker Desktop
first.

## 3. API server

### Environment variables

| Variable | Local value | Purpose |
|---|---|---|
| `DB_PASSWORD` | your local Postgres password | Required; no default |
| `ADMIN_API_TOKEN` | `local-dev-token` | Required; guards admin endpoints |
| `JPA_DDL_AUTO` | `validate` | Schema validation mode |
| `CACHE_TYPE` | `redis` | Cache provider |
| `REDIS_HOST` | `localhost` | |
| `REDIS_PORT` | `6379` | |

Full variable list and defaults are in
`services/api-server/src/main/resources/application.yaml`. Placeholders are in
`.env.example`. Never commit real values.

PowerShell:

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

CMD:

```cmd
cd services\api-server

set "DB_PASSWORD=your-local-postgres-password"
set "ADMIN_API_TOKEN=local-dev-token"
set "JPA_DDL_AUTO=validate"
set "CACHE_TYPE=redis"
set "REDIS_HOST=localhost"
set "REDIS_PORT=6379"

mvn spring-boot:run
```

bash:

```bash
cd services/api-server

export DB_PASSWORD='your-local-postgres-password'
export ADMIN_API_TOKEN='local-dev-token'
export JPA_DDL_AUTO='validate'
export CACHE_TYPE='redis'
export REDIS_HOST='localhost'
export REDIS_PORT='6379'

mvn spring-boot:run
```

Expected server log:

```text
Tomcat started on port 8080
Started TransitApplication
```

## 4. API smoke test

In another window:

```powershell
curl.exe -i 'http://localhost:8080/actuator/health'
```

On Linux or macOS use `curl` instead of `curl.exe`.

Expected:

```text
HTTP/1.1 200
{"status":"UP"}
```

Then run the functional endpoints:

```powershell
curl.exe -i 'http://localhost:8080/api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8'

curl.exe -i 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50'
```

Full expected results and timing interpretation are in
[`smoke_tests.md`](./smoke_tests.md).

## 5. Frontend

```powershell
cd services\web-client

npm.cmd install
npm.cmd run dev
```

On Linux or macOS use `npm` instead of `npm.cmd`.

Open:

```text
http://localhost:5173
```

## 6. Common failures

### Schema validation error on startup

Symptom:

```text
Schema-validation: missing table [analysis_table_final]
```

Fix: step 0 was not completed, or the server is pointed at a different database
than the one you created. Check `DB_URL`.

### PostgreSQL password missing

Symptom:

```text
The server requested SCRAM-based authentication, but no password was provided.
```

Fix: set `DB_PASSWORD` in the same terminal window that runs
`mvn spring-boot:run`, then restart the API server. Environment variables set in
one window do not carry to another.

### Port 5432 already in use

Symptom, when starting the compose `postgres` service:

```text
Bind for 0.0.0.0:5432 failed: port is already allocated
```

A PostgreSQL server is already running on the host. Either keep using the host
install and do not start the compose service, or map a different host port in
`.env`:

```text
POSTGRES_HOST_PORT=5433
```

Note that the two databases are separate — data loaded into one is not visible
in the other.

### Redis not running

Symptom:

```text
RedisConnectionFailureException: Unable to connect to Redis
Connection refused: localhost/127.0.0.1:6379
```

Fix, from the repository root:

```powershell
docker compose up -d redis
docker exec -it seoul-transit-redis redis-cli ping
```

Or switch to the Redis-free profile in section 7.

### Endpoints return 200 with empty results

Not an error. The schema exists but the tables hold no rows. Complete sections
4 and 5 of [`database_setup.md`](./database_setup.md).

### PowerShell vs CMD environment variables

PowerShell uses:

```powershell
$env:DB_PASSWORD='password'
```

CMD uses:

```cmd
set "DB_PASSWORD=password"
```

Do not mix these syntaxes.

## 7. Lightweight local profile without Redis

Use `local-simple` when only API/DB behavior needs to be checked.

```powershell
cd services\api-server

$env:SPRING_PROFILES_ACTIVE='local-simple'
$env:DB_PASSWORD='your-local-postgres-password'
$env:ADMIN_API_TOKEN='local-dev-token'
$env:JPA_DDL_AUTO='validate'

mvn spring-boot:run
```

This profile uses Caffeine instead of Redis. Rationale and trade-offs are in
[`docs/architecture/cache_profiles.md`](../architecture/cache_profiles.md).
