# Phase 6.9-2 District Demand Performance Optimization

## Summary

Phase 6.9 introduced district-centered all-route demand analysis. (Phase 6.9에서 행정동 중심 전체 노선 수요 분석을 도입했습니다.) The initial
implementation worked functionally, but local performance testing showed that
the district-demand endpoint could take roughly 9.5-12.9 seconds for
representative requests.

Phase 6.9-2 focused on making the existing query structure usable without
prematurely adding a precomputed `node_admin_dong_mapping` table.

## Endpoint tested

```text
GET /api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50
```

## Before optimization

Observed local timings:

| Run | Time |
|---:|---:|
| Run 1 | 12.879 s |
| Run 2 | 9.496 s |

The endpoint was functional but too slow for interactive map usage.

## Why it was slow

The query joins administrative boundary polygons against stop geometries at
request time, then joins the resulting node set against the hourly demand
table. Without supporting indexes this produced sequential scans over both the
boundary table and `integrated_hourly_transit_demand_light`, and the bus-side
join compared stop ids through `LPAD(...)` normalization, which a plain btree
index on the raw column cannot serve.

## Optimization applied

Indexes added or confirmed:

| Target | Type | Purpose |
|---|---|---|
| `admin_dong_boundary.geom` | GIST | Boundary polygon lookup |
| `bus_stop_location.geom` | GIST | Original stop geometry |
| `integrated_bus_stop_location.geom` | GIST | Integrated stop geometry |
| `integrated_hourly_transit_demand_light` | btree composite | `mode, day_type, hour, service_id, node_id` |
| `integrated_hourly_transit_demand_light` | expression, partial | `LPAD(node_id, 5, '0')` where `mode = 'bus'` |
| `bus_stop_location` | expression | `LPAD("정류장번호", 5, '0')` |
| `bus_stop_demand_node_mapping` | expression composite | `service_id` + normalized `demand_node_id` |
| `integrated_bus_stop_location.canonical_node_id` | btree | Canonical node lookup |

The expression indexes are the part worth noting: they exist specifically so
that the normalized-id comparisons in the join become index-usable rather than
forcing a scan.

Table statistics were then refreshed with `ANALYZE`.

SQL file:

```text
database/performance/phase6_9_2_district_demand_indexes.sql
```

## After optimization

| Condition | Result |
|---|---:|
| Post-index measured call | 490 ms |
| Cold call after cache flush | under 1 s |
| Best observed cold call | about 192 ms |

Approximate improvement from the original observed range:

| Baseline | After | Approximate improvement |
|---:|---:|---:|
| 9.5 s | 0.192 s | about 49x |
| 12.9 s | 0.192 s | about 67x |

## Reproducing this measurement

These numbers were taken on a local development machine against a fully loaded
database. To reproduce:

1. Load data per sections 4 and 5 of
   [`docs/deployment/database_setup.md`](../deployment/database_setup.md).
2. Measure before applying the index file.
3. Apply `database/performance/phase6_9_2_district_demand_indexes.sql`.
4. Flush the cache and measure again.

Applying the index file to an empty database succeeds but proves nothing —
`ANALYZE` has no rows to sample.

Flush Redis to measure cold behavior:

```powershell
docker exec -it seoul-transit-redis redis-cli FLUSHALL

Measure-Command {
  Invoke-RestMethod 'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50' | Out-Null
}
```

bash:

```bash
docker exec -i seoul-transit-redis redis-cli FLUSHALL

curl -s -o /dev/null -w '%{time_total}\n' \
  'http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9&nodeLimit=50'
```

## Interpretation 

The 6.9-2 result is strong enough to keep the current index-based structure.

`node_admin_dong_mapping` precomputation is not required immediately because
cold calls are already under one second in local testing.

## Deferred optimization

A precomputed mapping table may still be useful if GCP deployment or larger
query scopes show slower behavior.

Potential table:

```text
public.node_admin_dong_mapping
```

Potential columns:

```text
admin_dong_code
admin_dong_name
mode
service_id
node_id
node_name
lat
lng
```

This would remove request-time spatial joins by converting district membership
into a normal btree join. The trade-off is a materialized table that must be
rebuilt whenever stop locations or boundary definitions change.

## Current decision

```text
Status: Complete
Decision: Keep current query structure with performance indexes
Next phase: Phase 6.10 Deployment Readiness
```

## Portfolio framing

This optimization demonstrates a full backend performance workflow:

1. A geospatial demand endpoint was implemented.
2. Functional smoke tests passed.
3. Initial performance was measured and found too slow.
4. The cause was identified as unindexed spatial joins and non-sargable
   normalized-id comparisons.
5. Indexes, including expression indexes, and statistics were applied.
6. Cold-cache timing was re-measured.
7. Response time improved from roughly 10 seconds to sub-second.

This is stronger than stating that the endpoint exists; it shows
measurement-driven backend and geospatial database optimization, including the
decision *not* to add a precomputed table when indexing was sufficient.
