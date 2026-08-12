# Map Demand API

Reference for the `/api/map` endpoints that serve stop, station, catchment, and
administrative-district demand to the map client.

## Package layout

```text
services/api-server/src/main/java/com/ian/transit/map/
├─ controller/
│  ├─ MapDemandController.java      # /demand, /nodes/search, /node-detail,
│  │                                #   /node-catchment, /district-demand
│  └─ MapLineController.java        # /lines
├─ service/
│  └─ MapDemandService.java         # Parameter parsing and validation                              
├─ repository/
│  ├─ MapDemandRepository.java      # Route-selected demand
│  ├─ NodeDemandRepository.java     # Single-node all-route demand
│  ├─ NodeSearchRepository.java     # Keyword node lookup
│  ├─ NodeCatchmentRepository.java  # Radius-based demand
│  ├─ DistrictDemandRepository.java # District polygon demand
│  └─ support/
│     ├─ DemandSqlSupport.java      # Shared SQL fragments
│     ├─ DistanceCalculator.java
│     └─ NodeIdNormalizer.java      # 5-digit ARS padding
├─ dto/                             # 7 response records
└─ exception/
   └─ NodeNotFoundException.java
```

Supporting SQL:

```text
services/api-server/src/main/resources/sql/
├─ subway_station_join_alias.sql    # Station name alias corrections
├─ verify_subway_join.sql           # Subway join coverage check
├─ validate_bus_stop_coverage.sql
└─ validate_integrated_bus_stop_coverage.sql
```

## Endpoints

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/map/lines` | List available lines or routes for a mode |
| GET | `/api/map/demand` | Demand points for selected routes |
| GET | `/api/map/nodes/search` | Keyword search for stops and stations |
| GET | `/api/map/node-detail` | All-route demand at one node |
| GET | `/api/map/node-catchment` | All-route demand within a radius |
| GET | `/api/map/district-demand` | All-route demand inside districts |

`node-detail`, `node-catchment`, and `district-demand` are node-centered: they
aggregate every route touching the selected node or area, rather than filtering
to a route the user picked.

## Common parameters

| Parameter | Required | Default | Notes |
|---|---|---|---|
| `mode` / `modes` | varies | `subway,bus` | `subway` or `bus`; comma-separated for plural |
| `dayType` / `dayTypes` | yes (either) | — | At least one must be supplied |
| `dayAggregation` | no | `average` | `sum` or `average` only |
| `hour` / `hours` | yes (either) | — | At least one must be supplied |

Plural forms take precedence when both are given.

`mode`, `dayType`, and `dayAggregation` are all validated against fixed value
sets; an unrecognized value returns HTTP 400 rather than an empty result.

### Day type values

The demand tables store seven day types:

```text
mon  tue  wed  thu  fri  sat  sun_holiday
```

Sunday and public holidays are merged into a single `sun_holiday` bucket by the
Phase 6.3 and 6.5 pipelines — `sun` alone is not a valid value.

`MapDemandService.validateDayType()` checks each value against this list and
rejects anything else with HTTP 400.

```json
{
  "message": "unknown dayType 'sun'; allowed values are mon, tue, wed, thu, fri, sat, sun_holiday (Sunday demand is stored under 'sun_holiday')"
}
```

The check exists because an unrecognized day type matches no rows in SQL, which
would return HTTP 200 with zero demand — indistinguishable from a period that
genuinely has no demand. The same reasoning drives `unmatchedDistrictCodes` in
the district-demand response.

The allowed list is duplicated in `ALLOWED_DAY_TYPES` (Java) and `DAY_TYPES`
(`pipelines/hourly_stop_pattern/common.py`,
`pipelines/subway_integration_light/common.py`). If the pipelines ever add a
day type, both sides must be updated.

## Endpoint details

### GET /api/map/lines

```http
GET /api/map/lines?mode=subway
```

Returns a string array of line or route names.

### GET /api/map/demand

```http
GET /api/map/demand?mode=subway&dayTypes=mon,tue&dayAggregation=average&hours=8,9&lines=2호선
```

Accepts `line`/`lines` to restrict the result to selected routes.

Response element (`MapDemandResponse`):

```text
mode  serviceId  nodeId  nodeName  lat  lng  boarding  alighting
```

### GET /api/map/nodes/search

```http
GET /api/map/nodes/search?keyword=강남&limit=30
```

`limit` defaults to 30. Response element (`NodeSearchResponse`):

```text
mode  nodeId  nodeName  lat  lng
```

### GET /api/map/node-detail

```http
GET /api/map/node-detail?mode=bus&nodeId=01267&dayTypes=mon&hours=8
```

Throws `NodeNotFoundException` when the node id does not resolve.

Response (`NodeDemandDetailResponse`) carries node identity, totals, and a
per-route breakdown.

### GET /api/map/node-catchment

```http
GET /api/map/node-catchment?lat=37.5&lng=127.03&radiusMeters=800&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8
```

`radiusMeters` accepts **400, 800, or 1000 only**. Any other value raises
`IllegalArgumentException`.

Response (`NodeCatchmentDemandResponse`):

```text
centerLat  centerLng  radiusMeters  boarding  alighting  total  nodes[]  routes[]
```

### GET /api/map/district-demand

```http
GET /api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50
```

| Parameter | Notes |
|---|---|
| `districtCode` / `districtCodes` | `adm_cd` values from `admin_dong_boundary` |
| `nodeLimit` | 1–10000; truncates the returned node list only |

Response (`DistrictDemandResponse`):

```text
districts[]  unmatchedDistrictCodes[]  boarding  alighting  total
modes[]  routes[]  nodes[]  totalNodeCount
```

Two fields exist to prevent silent misreadings:

- `unmatchedDistrictCodes` lists requested codes that matched no boundary row,
  so a code-system mismatch surfaces instead of reading as zero demand.
- `totalNodeCount` is the full row count **before** `nodeLimit` truncation.
  Totals and breakdowns are always computed from the full row set.

This endpoint measures node activity inside the selected polygons. It is not OD
flow between districts.

## Bus join constraint

`MapDemandRepository.findBusMapDemand()` joins demand rows to bus coordinates
through a zero-padded ARS comparison:

```sql
JOIN bus_stop_location b
  ON LPAD(d.node_id::text, 5, '0') = LPAD(b."정류장번호"::text, 5, '0')
```

Two consequences:
1. `bus_stop_location` must expose `정류장번호`, `경도`, and `위도`. If the
   table is replaced with a different structure, this method must be edited.
2. A plain btree index on the raw column cannot serve this comparison. The
   expression indexes added in Phase 6.9-2 exist for exactly this reason.

See `docs/performance/phase6_9_2_district_demand_optimization.md`.

## Caching

Map endpoints are **not** annotated with `@Cacheable`. Only
`CongestionQueryService` and `SeoulBusApiClient` cache at the method level.

District-demand latency therefore depends on database indexes rather than a
cache layer, which is why the Phase 6.9-2 measurements were taken cold.

## Related documents

| Document | Contents |
|---|---|
| `docs/architecture/overview.md` | System design and pipeline |
| `docs/architecture/cache_profiles.md` | Redis/Caffeine profiles |
| `docs/deployment/database_setup.md` | Tables these endpoints read |
| `docs/deployment/smoke_tests.md` | Runnable request examples |
