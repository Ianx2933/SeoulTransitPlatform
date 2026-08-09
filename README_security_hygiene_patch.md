# Security & Hygiene Patch (review items 1–14 + frontend test runner)

This patch fixes the issues found in the full-repository code review, except
password rotation which is a manual operational step (see below).

## How to apply

1. Copy every file in this ZIP over the same relative path in
   `SeoulTransitPlatform` (same copy-over style as previous phase patches).
2. Run `scripts/cleanup_repo_hygiene.bat` (Windows) or
   `scripts/cleanup_repo_hygiene.sh` once from the repo root.
3. `cd services/web-client && npm install` (adds vitest, moves build tools to
   devDependencies — package-lock.json will be regenerated).
4. Set environment variables before starting the API server (see below).
5. `cd services/api-server && mvn clean test` — this patch was written without
   a compiler available, so run a full build before committing.

## Required environment variables (new)

| Variable | Purpose |
|---|---|
| `DB_PASSWORD` | PostgreSQL password — **no longer committed in application.yaml** |
| `ADMIN_API_TOKEN` | Shared token for `/api/od-correction/**`; endpoints are locked (503) while unset |
| `SEOUL_BUS_API_KEY` | Seoul bus master API key (optional) |
| `DB_URL`, `DB_USERNAME`, `JPA_DDL_AUTO`, `PREDICTION_SERVICE_BASE_URL` | Optional overrides |
| `CACHE_TYPE`, `CACHE_TTL`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_TIMEOUT` | Redis cache overrides; Caffeine is available through `local-simple` profile |

## What changed

### 1. SQL injection fixed — `OdCorrectionJdbcRepository`
`deduplicateAndSum` interpolated the HTTP `date` parameter into
`CREATE TEMP TABLE ... AS SELECT`. PostgreSQL does not accept bind parameters
inside CTAS, so the statement was split: the temp table is created empty
(`WHERE false`, no user input) and populated by a fully parameterized
`INSERT ... SELECT ... WHERE 기준일자 = ?`.

### 1b. Defense in depth — `CorrectionDateValidator` (new)
Every public entry point of `OdCorrectionOrchestrator` and
`CorrectionQueryService` now enforces the 8-digit `yyyyMMdd` shape, so no
correction workflow can ever receive SQL metacharacters regardless of how a
future query is written.

### 2. Admin endpoints protected — `AdminApiSecurityConfig` (new)
`/api/od-correction/**` (destructive UPDATE/DELETE workflows) now require an
`X-Admin-Token` header matching `ADMIN_API_TOKEN`. Fail-closed: while the
token is unconfigured, all requests get 503. Constant-time comparison. Full
Spring Security was intentionally avoided for a single admin surface.

### 3. Secrets removed from config
`application.yaml` now reads credentials from environment variables — the
committed plaintext password is gone. `run_phase6_2_example.bat` no longer
embeds the password and refuses to run without `DB_PASSWORD` set.
`.env.example` documents the pipeline key without containing one.

> **Manual steps you still must do (excluded from this patch by request):**
> the old DB password and the Seoul API key remain in git history and in
> previously shared archives. Rotate both when convenient — config changes
> alone do not un-leak them.

### 4. `.env` handling
`.env.example` added; README warning about never sharing the real `.env`.

### 5. Exception handler hardened — `GlobalExceptionHandler`
5xx responses now return a fixed message; the stack trace goes to the SLF4J
log instead of `printStackTrace` / the HTTP body. 4xx behavior unchanged
(validation messages remain visible, matching the frontend `fetchJson`
policy). New: `UnsupportedOperationException` → HTTP 501.

### 6. Caching actually enabled — Redis by default, Caffeine by profile

`@EnableCaching` added — every `@Cacheable` in the codebase was previously a
silent no-op. Redis is retained as the default cache provider for deployment
parity with GCP-style managed Redis/Memorystore environments. Caffeine remains
available through the `local-simple` profile for lightweight local development
without external Redis.

(`@EnableCaching`을 추가해 기존 `@Cacheable` 메서드가 실제로 동작하게 했다.
GCP의 managed Redis/Memorystore 계열 배포 구조와 맞추기 위해 Redis를 기본 cache
provider로 유지한다. 외부 Redis 없이 가볍게 로컬 실행할 때는 `local-simple`
profile에서 Caffeine을 사용한다.)

Tests use `TestcontainersConfiguration` with `@ServiceConnection(name = "redis")`
so Spring Boot can create Redis connection details from the generic Redis
container.

(테스트에서는 `@ServiceConnection(name = "redis")`를 사용해 GenericContainer 기반
Redis container에서도 Spring Boot가 Redis connection details를 생성할 수 있게 한다.)


### 7. Multi-route congestion fixed — `CongestionQueryService`
One route with no data no longer aborts the whole multi-route request; empty
routes are skipped with a warning and 404 is thrown only when every route was
empty. Internal calls to `getRouteCongestion` now go through a lazily
self-injected proxy so `@Cacheable` is not bypassed by self-invocation.

### 8. Greedy regex fixed — `MapDemandRepository`, `NodeSearchRepository`
`\(.*\)` → `\([^)]*\)` (station names with two parenthetical groups lost the
text between them). All three demand repositories now use the identical
pattern; `DistrictDemandRepository` already had the fix from the r2 patch.

### 9. Catchment pushed down to PostGIS — `NodeCatchmentRepository`
The radius filter now runs in SQL (`ST_DWithin` on geography) with per-node
distance from `ST_Distance`, instead of loading the entire city's demand rows
into Java per request. Java-side node/route grouping is unchanged, so the
response contract is identical. Requires PostGIS (already required by the
district-demand feature).

### 10. Prediction weight update is honest end-to-end
Flask `/weight/update` validates the payload and returns **501** (persistence
is not implemented) instead of a fake 204. `PredictionClient` translates that
501 into `UnsupportedOperationException`, which the exception handler maps to
HTTP 501 — callers can no longer believe a weight was stored.

### 11–13. Repository hygiene — `.gitignore`, `scripts/cleanup_repo_hygiene.*`
`node_modules/` and `dist/` ignore rules added (the missing `node_modules`
rule is how ~4,500 files / ~108 MB got committed). The cleanup script
untracks node_modules and the large data files (ignore rules do not affect
already-tracked files) and deletes the two stray empty artifacts
(`Number(b[metric]`, `services/api-server/java`).

### 14. Config hardening — `application.yaml`
`ddl-auto` changed from `update` to `validate` (override with `JPA_DDL_AUTO`):
the schema is owned by the SQL pipelines, so Hibernate must never mutate it.
If startup now fails with a schema-validation error, that is a real
entity/schema drift being surfaced — fix the mapping rather than reverting to
`update`.

### Frontend test runner
`vitest` added with `npm test` / `npm run test:watch` scripts. The existing
`nodeDetailSort.test.js` (which already imported from vitest but could never
run) now executes, and a new `districtDemandSort.test.js` suite covers the
second sort util. `vite` and `@vitejs/plugin-react` moved to
`devDependencies`.

### Follow-up fixes (review round 2)

**Flask 5xx detail exposure removed** — `app.py`'s generic handlers returned
`"detail": str(error)` in 500 bodies, contradicting the Spring-side policy.
Both `/predict` and `/weight/update` now log the full traceback via
`app.logger.exception` and return a fixed error message only.

**RestTemplate timeouts** — the project already provides the
`RestTemplate` bean in `common/config/RestClientConfig.java` (so
`PredictionClient` injection works and no `HttpClientConfig` is needed — do
NOT add a second bean, it would be redundant). However the existing bean was
`new RestTemplate()` with infinite timeouts; the patched `RestClientConfig`
uses `SimpleClientHttpRequestFactory` with connect 3s / read 30s so a hung
downstream can no longer pin servlet threads indefinitely. If model inference
can legitimately exceed 30s, raise READ_TIMEOUT accordingly.

(`new RestTemplate()`은 timeout이 무한이었으므로, 수정된 `RestClientConfig`는
`SimpleClientHttpRequestFactory`로 connect 3초 / read 30초 timeout을 명시한다.)

## Verification checklist

```powershell
# start local Redis when using the default cache profile
# (기본 cache profile을 사용할 때 로컬 Redis를 실행합니다)
docker compose up -d redis

# backend compiles and unit tests pass
cd services\api-server
mvn clean test

# admin endpoints fail closed without a token
Invoke-WebRequest -Method POST "http://localhost:8080/api/od-correction/deduplicate?date=20250101"   # expect 503
$env:ADMIN_API_TOKEN="local-dev-token"  # restart server, then:
Invoke-WebRequest -Method POST -Headers @{"X-Admin-Token"="local-dev-token"} "http://localhost:8080/api/od-correction/deduplicate?date=20250101"  # expect 200
Invoke-WebRequest -Method POST -Headers @{"X-Admin-Token"="local-dev-token"} "http://localhost:8080/api/od-correction/deduplicate?date=20250101';--"  # expect 400

# catchment returns identical shape using the supported radiusMeters parameter (지원되는 radiusMeters 파라미터로 응답 형태를 확인합니다)
Invoke-RestMethod "http://localhost:8080/api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8"

# frontend tests
cd services\web-client
npm install
npm test
```
# Security Hygiene Follow-up Patch 1-2-3

This patch contains only the three requested follow-up fixes.

## Included changes

1. `README_security_hygiene_patch.md`
   - Fixes the node-catchment verification URL from `radius=500` to `radiusMeters=800`.

2. `scripts/cleanup_repo_hygiene.sh`
   - Adds `--ignore-unmatch` to every `git rm` command.
   - Uses `set -euo pipefail`.

3. `scripts/cleanup_repo_hygiene.bat`
   - Adds `--ignore-unmatch` to every `git rm` command.

4. `NodeCatchmentRepository.java`
   - Excludes the bus placeholder node id `00000` in both primary and integrated bus catchment branches.
   - Does not add SQL bind parameters, so the existing JDBC parameter order is unchanged.

## Suggested verification

```cmd
findstr /N "radius=500" README_security_hygiene_patch.md
findstr /N "radiusMeters=800" README_security_hygiene_patch.md
findstr /N "--ignore-unmatch" scripts\cleanup_repo_hygiene.sh
findstr /N "--ignore-unmatch" scripts\cleanup_repo_hygiene.bat
findstr /N "00000" services\api-server\src\main\java\com\ian\transit\map\repository\NodeCatchmentRepository.java
```


## Redis/Caffeine profile follow-up

This follow-up keeps Redis as the default cache provider and adds a
`local-simple` Caffeine profile.

(이 follow-up은 Redis를 기본 cache provider로 유지하고, Caffeine을 사용하는
`local-simple` profile을 추가한다.)

```cmd
REM Default mode: Redis cache. (기본 모드: Redis cache)
docker compose up -d redis
cd services\api-server
set DB_PASSWORD=change-me
set ADMIN_API_TOKEN=local-dev-token
mvn clean test

REM Lightweight local mode: Caffeine cache. (경량 로컬 모드: Caffeine cache)
set SPRING_PROFILES_ACTIVE=local-simple
mvn spring-boot:run
```
