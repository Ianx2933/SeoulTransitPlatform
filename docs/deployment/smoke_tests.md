# Smoke Tests

This document lists the minimum smoke tests for local verification after
starting the API server. See [`local_runbook.md`](./local_runbook.md) for startup.

All paths are relative to the repository root. Examples use `curl.exe` and
PowerShell; on Linux or macOS use `curl` and the bash equivalents noted inline.

## Important PowerShell rule

Use raw URLs only. Do not paste Markdown links into PowerShell.

Correct:

```powershell
curl.exe -i 'http://localhost:8080/actuator/health'
```

Incorrect:

```powershell
curl.exe -i '[http://localhost:8080/actuator/health](http://localhost:8080/actuator/health)'
```

## 1. Health

```powershell
curl.exe -i 'http://localhost:8080/actuator/health'
```

Expected:

```text
HTTP/1.1 200
{"status":"UP"}
```

If health returns `503 DOWN`, check PostgreSQL and Redis. A `DOWN` result with
the default profile most often means Redis is not running — see section 5.

## 2. Node catchment API

```powershell
curl.exe -i 'http://localhost:8080/api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8'
```

Expected:

- HTTP 200.
- JSON response body.
- No internal stack trace returned to client.
- No fake bus stop id `00000` in returned node results.

Optional check:

```powershell
curl.exe 'http://localhost:8080/api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8' | findstr "00000"
```

bash equivalent:

```bash
curl -s 'http://localhost:8080/api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8' | grep "00000"
```

Expected:

```text
(no output)
```

## 3. District demand API

Single district:

```powershell
curl.exe -i 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50'
```

Multiple districts:

```powershell
curl.exe -i 'http://localhost:8080/api/map/district-demand?districtCodes=11230760,11140760,11150660&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50'
```

Heavier condition:

```powershell
curl.exe -i 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=6,7,8,9,10,17,18,19,20&nodeLimit=100'
```

The district codes above are `adm_cd` values in `admin_dong_boundary`. If the
response is empty, confirm the code exists:

```sql
SELECT adm_cd, adm_nm FROM public.admin_dong_boundary WHERE adm_cd = '11230760';
```

## 4. Performance timing

Single district timing:

```powershell
Measure-Command {
  Invoke-RestMethod 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50' | Out-Null
}
```

Cold timing after Redis flush:

```powershell
docker exec -it seoul-transit-redis redis-cli FLUSHALL

Measure-Command {
  Invoke-RestMethod 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50' | Out-Null
}
```

bash equivalent:

```bash
docker exec -i seoul-transit-redis redis-cli FLUSHALL

curl -s -o /dev/null -w '%{time_total}\n' \
  'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50'
```

Interpretation:

| Result | Meaning |
|---:|---|
| under 300 ms | Excellent local result |
| 300-800 ms | Strong MVP/API result |
| 800 ms-2 s | Acceptable, monitor before deployment |
| 2-5 s | Optimize query plan or precompute mapping |
| over 5 s | Request-time spatial/demand join is too heavy |

Timings above 2 s on a freshly loaded database usually mean planner statistics
are stale. Re-run:

```bash
psql -h localhost -p 5432 -U postgres -d Seoul_Transit \
  -f database/performance/phase6_9_2_district_demand_indexes.sql
```

Background in
[`docs/performance/phase6_9_2_district_demand_optimization.md`](../performance/phase6_9_2_district_demand_optimization.md).

## 5. Failure diagnosis

### 500 on API endpoints

Check the API server console. The client response intentionally hides internal
details.

Look for:

```text
Caused by:
org.postgresql.util.PSQLException
RedisConnectionFailureException
```

A `PSQLException` naming a missing relation means a table was never created.
Only `analysis_table_final` is checked at startup; every other table fails at
request time instead. See
[`database_setup.md`](./database_setup.md).

### Health DOWN due to DB

Typical cause:

```text
The server requested SCRAM-based authentication, but no password was provided.
```

Fix: set `DB_PASSWORD` in the API server terminal and restart.

### Health DOWN due to Redis

Typical cause:

```text
RedisConnectionFailureException: Unable to connect to Redis
```

Fix, from the repository root:

```powershell
docker compose up -d redis
docker exec -it seoul-transit-redis redis-cli ping
```

### 200 with empty results

Not a failure of the API. The schema exists but the tables are empty. Load data
per sections 4 and 5 of [`database_setup.md`](./database_setup.md).
