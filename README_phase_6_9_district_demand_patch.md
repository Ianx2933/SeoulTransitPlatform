# Phase 6.9 District Demand Patch (r2 — review fixes applied)

This patch adds district-centered all-route demand analysis.

## What it adds

- `GET /api/map/district-demand`
- Backend SQL repository for district-based bus/subway demand aggregation
- Frontend `fetchDistrictDemand`
- `DistrictDemandPanel.jsx`
- Route-level and node-level district demand breakdowns
- `Add to selected routes` support from district route rows

## Changes in r2 (review fixes)

1. **Boundary double-count fix** — district filtering now uses `EXISTS` against
   `district_scope` instead of `JOIN`, so a node lying exactly on a shared
   boundary of two selected districts is counted once.
2. **Join fan-out protection** — `subway_station_location`,
   `bus_stop_location`, and `bus_stop_demand_node_mapping` are deduplicated
   with `DISTINCT ON` before joining, so duplicate source rows can no longer
   multiply demand.
3. **Regex fix** — parenthetical stripping now uses the non-greedy-safe
   pattern `\([^)]*\)` instead of `\(.*\)`, which destroyed text between two
   parenthetical groups.
4. **Unmatched district detection** — the response now includes
   `unmatchedDistrictCodes` for requested codes that matched no boundary row.
   The panel shows a warning so a code-system mismatch (8-digit vs 10-digit
   `adm_cd`) no longer silently reads as zero demand.
5. **Optional `nodeLimit`** — `GET /api/map/district-demand` accepts
   `nodeLimit` (1–10000). Truncation happens AFTER aggregation, so totals,
   mode breakdowns, and route breakdowns are always computed from all rows.
   `totalNodeCount` in the response reports the full row count.
6. **Truncation indicators** — the panel labels route/node list truncation
   ("Showing top N of M") instead of cutting silently.
7. **District selection persists across Load demand** — district-centered
   demand refetches automatically with the new filters instead of being
   cleared.
8. **4xx error messages surfaced** — backend validation messages (e.g.
   "districtCode must contain 8 to 10 digits") now reach the UI. 5xx bodies
   remain console-only.
9. Minor: composition instead of inheritance for accumulators, explicit
   per-mode route-selection check, `filtersApplied` named dependency,
   consistent formatting in `DataCoverageNotice.jsx`.

## Interpretation rule

District demand means stop/station node activity inside selected
administrative polygons. It is **not** district-to-district OD flow, transfer
inference, onboard load, or crowding.

## DB prerequisite

The following tables/columns are expected:

```sql
public.admin_dong_boundary(adm_cd, adm_nm, geom)
public.bus_stop_location("정류장번호", "정류장명", "위도", "경도", geom)
public.integrated_bus_stop_location(canonical_node_id, stop_name, lat, lng, geom)
public.subway_station_location(line_name, station_name, lat, lng)
public.integrated_hourly_transit_demand_light(mode, service_id, node_id, node_name, day_type, hour, boarding, alighting)
public.bus_stop_demand_node_mapping(service_id, demand_node_id, canonical_node_id)
```

`00000` bus placeholder nodes are excluded from district-demand SQL.

**Node-id assumption**: bus node ids are normalized with
`LPAD(node_id, 5, '0')`. PostgreSQL `LPAD` truncates strings longer than 5
characters, so bus node ids in the source data must be at most 5 digits.

**adm_cd code system**: the `ADM_CD` values in the frontend GeoJSON layer and
`admin_dong_boundary.adm_cd` must use the same code system (both 8-digit or
both 10-digit). Mismatches now surface as an `unmatchedDistrictCodes` warning
in the panel.

## Recommended expression indexes

The bus joins compare `LPAD(...)` expressions on both sides, which plain
column indexes cannot serve. Create these once:

```sql
CREATE INDEX IF NOT EXISTS idx_bus_stop_location_stop_key
    ON public.bus_stop_location (lpad("정류장번호"::text, 5, '0'));

CREATE INDEX IF NOT EXISTS idx_bus_stop_demand_node_mapping_key
    ON public.bus_stop_demand_node_mapping (service_id, lpad(demand_node_id::text, 5, '0'));

CREATE INDEX IF NOT EXISTS idx_ihtdl_bus_node_key
    ON public.integrated_hourly_transit_demand_light (lpad(node_id::text, 5, '0'))
    WHERE mode = 'bus';
```

After creating them, verify the plan uses them:

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT ...;  -- run the district-demand query captured from the server log
```

## Files

Copy the files in this ZIP over the same relative paths in
`SeoulTransitPlatform`. Note that `TransitDemandMap.jsx` is a full-file
replacement — if you have local edits, diff before overwriting.

## Smoke tests

```powershell
cd C:\Users\miyum\SeoulTransitPlatform\services\api-server
mvn spring-boot:run
```

```powershell
Invoke-RestMethod "http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon,tue,wed,thu,fri&dayAggregation=average&hours=7,8,9"
```

```powershell
# nodeLimit truncation: totals must be identical with and without nodeLimit
Invoke-RestMethod "http://localhost:8080/api/map/district-demand?districtCode=11230760&modes=bus,subway&dayTypes=mon&dayAggregation=average&hours=8&nodeLimit=10"
```

```powershell
# unmatched code detection: expect unmatchedDistrictCodes to contain the code
Invoke-RestMethod "http://localhost:8080/api/map/district-demand?districtCode=99999999&modes=bus&dayTypes=mon&dayAggregation=average&hours=8"
```

```powershell
cd C:\Users\miyum\SeoulTransitPlatform\services\web-client
npm.cmd run dev
```

Additional manual checks:

- Select two adjacent districts (Ctrl+click) and confirm the district total
  equals the sum of per-node rows (no boundary double count).
- Change day/hour filters and click Load demand with districts selected; the
  district panel should reload with the new filters instead of disappearing.
