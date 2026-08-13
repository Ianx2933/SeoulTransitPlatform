# Data Quality Troubleshooting — SQL Server Era

Problems encountered and resolved during the predecessor project, when the
platform ran on SQL Server Express with a Folium-based visualisation layer.

(전신 프로젝트 시절 — SQL Server Express + Folium 구성에서 — 마주치고 해결한
문제들의 기록입니다.)

This is a historical record. The stack described here has been replaced:
PostgreSQL/PostGIS instead of SQL Server, React/Leaflet instead of Folium. The
problems are recorded because several of them shaped decisions that still hold
in the current codebase, and because the reasoning transfers even where the
syntax does not.

For how the system works now, see
[`docs/architecture/overview.md`](../../architecture/overview.md).

## Summary

| # | Problem | Tool | Resolution |
|---:|---|---|---|
| 1 | Visualisation tool selection | Folium | Interactive map driven by the Spring Boot API |
| 2 | Single-date data limitation | CSV swap | Date-independent pipeline design |
| 3 | ARS ↔ standard code matching | SQL | Composite join on ARS + stop name |
| 4 | Coordinate mismatching | SQL | Composite join + coordinate range filter |
| 5 | BOM character | SQL | SUBSTRING instead of REPLACE |
| 6 | Virtual stops and depots | SQL + Python | Filter ARS != 00000, clamp negative load |
| 7 | Boarding/alighting sequence conflict | Python | boarding_seq + 1 correction |
| 8 | ARS zero padding | Python | str.zfill(5) |
| 9 | NULL standard codes | SQL | Self-join backfill |
| 10 | Processing speed | Spring Boot + Python | 28 min → 10 s |
| 11 | Out of memory | SQL | Cache flush before execution |
| 12 | Slow LIKE queries | SQL | Index on searched columns |
| 13 | Hibernate 7.x incompatibility | Java | Switch to JdbcTemplate |
| 14 | Static analysis → dynamic pipeline | Spring Boot | REST API |

---

## 1. Visualisation tool replaced three times

**Tableau** — Linking congestion colour to stop coordinates required custom
coordinate mapping that proved awkward, and per-stop dynamic colouring hit a
hard limit. Abandoned.

**QGIS** — Segment-level congestion colouring worked, and prior experience made
it quick to build. But QGIS produced static images only; stop-click interaction
and OD popups were not possible. Abandoned.

**Folium** — Connected directly to the Spring Boot API for dynamic data.
Per-route layers, OD popups, and congestion gradients all worked, and output
could be shared as a single URL through GitHub Pages. Adopted.

**A fourth change followed.** Folium was itself replaced by React and Leaflet
during the move toward cloud deployment. Folium generates a static HTML file
with all data inlined — a single map in the predecessor project reached
11.9 MB — which does not fit a server-rendered, filter-driven application. The
current web client queries the API per interaction instead of baking data into
the artefact.

The sequence Tableau → QGIS → Folium → React/Leaflet reflects a tool being
re-evaluated each time the requirements changed, rather than being kept because
it was already learned.

(도구를 이미 익혔다는 이유로 유지하지 않고, 요구사항이 바뀔 때마다 다시
평가한 결과입니다.)

## 2. Single-date data limitation

Only one date of CSV data was available initially, making time-of-day and
day-of-week analysis impossible.

Additional dated CSVs were obtained from the public data portal and the schema
was designed so files could be swapped: bulk-insert a new CSV into
`Analysis_Table_Final` and the analysis runs unchanged. The pipeline itself is
date-independent.

**Lesson.** Design for a wide date range during collection, not after.

Deeper data — transfer patterns, rider-type records — remains accessible only
through Korea's data safe zone under privacy law.

## 3. ARS code only → 9-digit standard code matching

Source OD data carried 5-digit ARS codes; the location master carried 9-digit
standard codes. No common join key existed.

**Attempt 1 — join on ARS alone.** The same ARS code exists separately in
Seoul, Gyeonggi, and Incheon. ARS `22026` resolves to both Namtaeryeong Station
in Seoul and Hyeopdong Village in Guri.

**Attempt 2 — join on the 9-digit standard code.** Nationally unique in theory,
but the source data contained mis-entered Gyeonggi codes. Using
`MAX(standard_code)` then selected the numerically larger Gyeonggi code.

**Resolution — composite join on ARS + stop name, bounded by coordinates.**

```sql
ON b.ARS코드 = r.ARS
AND b.정류장명 = r.정류장명
AND 맵핑좌표Y_F BETWEEN 37.0 AND 38.5
AND 맵핑좌표X_F BETWEEN 126.0 AND 128.0
```

This resolves ARS duplication and mis-entered standard codes at once, while the
coordinate bounds keep both Seoul and Gyeonggi wide-area routes in scope.
Residual NULLs from stop-name mismatches stayed under 1%, documented as a data
quality limit rather than hidden.

**This decision still holds.** The current `integrated_bus_stop_location` table
carries `match_method` and `match_confidence` columns for exactly this reason —
a matched row records how it was matched.

## 4. Coordinate mismatching from duplicate ARS codes

A joined ARS produced Gyeonggi coordinates for a Seoul stop: `NH농협은행자양로지점`
in Jayang-dong, Seoul resolved to a Gyeonggi location.

ARS `05148` carries both a Seoul standard code (`104000055`) and a Gyeonggi one
(`204000026`); `MAX()` selected the larger Gyeonggi value.

Resolved by the composite join in item 3.

## 5. BOM character handling

Bulk-inserting CSV introduced a byte order mark (`\xEF\xBB\xBF`) into the first
column. `REPLACE(노드ID, CHAR(65279), '')` returned NULL rather than stripping it.

```sql
SUBSTRING(노드ID, 2, LEN(노드ID))
```

**Lesson.** When REPLACE returns NULL, SUBSTRING is a viable workaround.

## 6. Virtual stops and depot handling

**Virtual stops.** Depot departures and temporary stops carry ARS codes of NULL
or `00000`. These have no coordinates and break congestion calculation.

```sql
WHERE 승차_정류장ARS != '00000'
AND 하차_정류장ARS != '00000'
```

The current smoke tests still check that no `00000` appears in map results —
see [`docs/deployment/smoke_tests.md`](../../deployment/smoke_tests.md).

**Negative onboard load at depots.** When every passenger alights at a terminus,
cumulative onboard count can go negative.

```sql
CASE WHEN r.재차량 < 0 THEN 0 ELSE ROUND(r.재차량 / 19.0, 0) END AS 재차량
```

**Sequence correction.** Some rows had identical or inverted boarding and
alighting sequence numbers, caused by BMS logic errors. Corrected in Python.

## 7. Boarding/alighting sequence conflict

Rows where boarding and alighting stops shared a sequence number or name.

Handled in Python rather than SQL:

```python
if boarding_seq == alighting_seq:
    alighting_seq += 1
```

The rule is business logic applied row by row. A SQL `CASE WHEN` chain would
have been harder to read and maintain than a Python `apply`.

## 8. ARS zero padding

`07511` stored as `7511`. Storing ARS as an integer drops the leading zero.

**Root fix, in Python at ingest:**

```python
str(x).zfill(5)
```

**Retroactive fix, in SQL:**

```sql
RIGHT('00000' + CAST(ARS AS VARCHAR), 5)
```

**Lesson.** Force VARCHAR rather than INT for identifier columns; the leading
zero carries meaning.

This one has a long tail. The current codebase still normalises with
`LPAD(node_id, 5, '0')` at join time, which is why Phase 6.9-2 needed
*expression* indexes rather than plain btree ones — a padded comparison is not
sargable against a raw column. See
[`docs/performance/phase6_9_2_district_demand_optimization.md`](../../performance/phase6_9_2_district_demand_optimization.md).

## 9. NULL standard codes

Rows with a valid ARS but a NULL standard code.

Handled in SQL rather than Python: the mapping already existed in other rows
and in the master table, so a set-based join updated everything at once.

```sql
UPDATE a
SET a.표준코드 = m.표준코드
FROM Analysis_Table_Final a
JOIN STATION_MASTER m ON a.승차_정류장ARS = m.ARS
WHERE a.승차_정류장표준코드 IS NULL
```

This became the first stage of the four-stage fallback correction that the
current `pipelines/od_correction/` implements.

## 10. SQL-only processing → automated pipeline: 28 min → 10 s

Three correction stages — virtual stops, ARS mismatches, duplicate sequences —
were originally run as manual SQL, taking about 28 minutes per execution.

Correction logic was moved into Spring Boot REST endpoints, with Python calling
the API, caching to CSV, and generating the map. Full pipeline execution
dropped to about 10 seconds.

```text
Manual SQL (28 min)
        ↓
Spring Boot API automation
        ↓
Python CSV caching
        ↓
Map generation (10 s)
```

The gain is not raw compute speed. It is the removal of manual steps, which is
also what made the run repeatable.

## 11. SQL Server Express out of memory

Complex CTE and window function queries failed:

```text
SQL Error 802: insufficient memory
```

SQL Server Express caps memory at 1.4 GB. Flushing caches before execution
worked around it:

```sql
DBCC FREEPROCCACHE;
DBCC DROPCLEANBUFFERS;
```

This was a workaround, not a fix, and it was one of the reasons for moving to
PostgreSQL.

## 12. Leading-wildcard LIKE causing slow queries

`LIKE '%가상%'` cannot use an index and forces a full table scan.

```sql
CREATE INDEX IX_Analysis_승차정류장명
ON Seoul_Transit.dbo.Analysis_Table_Final (승차_정류장명);

CREATE INDEX IX_Analysis_하차정류장명
ON Seoul_Transit.dbo.Analysis_Table_Final (하차_정류장명);
```

**Lesson.** Index frequently searched columns; avoid leading wildcards.

This is the same class of problem as Phase 6.9-2 — a query written in a form
the planner cannot serve from an index. The later work applied the same
reasoning to spatial joins and padded-identifier comparisons.

## 13. Hibernate 7.x incompatibility

After upgrading Spring Boot and Hibernate, JPQL rejected window functions such
as `ROW_NUMBER()` under stricter parsing.

Switched from JPA repositories to `JdbcTemplate`, executing native SQL for CTE
and window function queries.

```java
return jdbcTemplate.query(sql,
    (rs, rowNum) -> new CongestionDto(...),
    routeName, routeName);
```

**This shaped the current architecture.** Only one table in the present codebase
has a JPA `@Entity` — `analysis_table_final`. Every other table is read through
`JdbcTemplate`. That is why `ddl-auto: validate` only guards one table at
startup, and why a missing table elsewhere fails at request time instead. See
[`docs/deployment/database_setup.md`](../../deployment/database_setup.md).

## 14. Static analysis → dynamic pipeline

CSV-based analysis was one-off: changing the route under study meant
reprocessing a CSV by hand, satisfying neither reproducibility nor scale.

Replaced with Spring Boot REST endpoints:

```text
GET /api/congestion/{route}
GET /api/congestion?routes=143,345
GET /api/od/{route}/{ars}
GET /api/od/all
```

Changing a route name was enough to query new data. Python called the API,
cached to CSV, and generated the map.

**Endpoint paths have since changed.** `/api/od/*` was replaced by
`/api/curated-od/*`, and the map endpoints under `/api/map/*` were added later.
See [`docs/architecture/map_demand_api.md`](../../architecture/map_demand_api.md).
