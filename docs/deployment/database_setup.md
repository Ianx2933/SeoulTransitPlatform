# Database Setup

This document describes how to create the `Seoul_Transit` PostgreSQL/PostGIS
database from an empty server, and in what order to load data into it.

`docs/deployment/local_runbook.md` assumes this has already been completed.

## Why this step cannot be skipped

`services/api-server/src/main/resources/application.yaml` sets:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: ${JPA_DDL_AUTO:validate}
```

Hibernate validates the schema and never creates it. The API server will not
start against a database that does not already contain the mapped table.

## Two levels of completeness

There are two useful stopping points. Pick the one that matches what you need.

| Level | What you get | What you need |
|---|---|---|
| Structure only | API server boots; endpoints return empty results | This document, sections 1-3 |
| Structure + data | Map and demand endpoints return real results | Sections 1-5 |

The distinction matters because only one table is mapped by a JPA entity.

### Boot requirement

`analysis_table_final` is the only table with a JPA `@Entity`
(`com.ian.transit.curatedod.infrastructure.CuratedOdRecord`). If it is missing
or its columns do not match, startup fails with a Hibernate schema validation
error.

Every other table is read through `JdbcTemplate`. A missing table there does
not block startup — it produces a `PSQLException` at request time instead,
surfacing as HTTP 500 on the affected endpoint.

So: a database created with structure only will start the server and answer
`/actuator/health` with `UP`, while map endpoints return empty arrays.

## 0. psql setup on Windows

Two things trip up a first run on a Korean Windows install.

### psql is not on PATH

The PostgreSQL installer does not add it. Either call it by full path or add
it to PATH for the session:

```cmd
set "PATH=%PATH%;C:\Program Files\PostgreSQL\18\bin"
psql --version
```

Adjust the version folder to match your install.

### Client encoding must be UTF8

Column names in this schema are Korean. A Korean Windows psql defaults to the
UHC encoding and fails with:

```text
ERROR: character with byte sequence 0xa4 0x80 in encoding "UHC" has no
equivalent in encoding "UTF8"
```

`run_all.sql` sets `client_encoding` itself, so the full run works. For ad-hoc
commands against individual files, set it for the session:

```cmd
set "PGCLIENTENCODING=UTF8"
```

If psql's own messages appear garbled in the console, that is a code page
display issue, not a data problem. `chcp 65001` fixes the display.

## 1. Create the database

```bash
psql -h localhost -p 5432 -U postgres -c "CREATE DATABASE \"Seoul_Transit\" ENCODING 'UTF8';"
```

The database name is case-sensitive here because the default `DB_URL` in
`application.yaml` points at `Seoul_Transit`. If you use a different name,
override `DB_URL` when starting the API server.

## 2. Confirm PostGIS is available

PostGIS must be installed on the server before the extension can be enabled.
On Windows this is a component of the PostgreSQL installer (Application
Stack Builder); on Debian/Ubuntu it is the `postgresql-NN-postgis-3` package.

## 3. Create the schema

From the repository root:

```bash
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -f database/schema/run_all.sql
```

Verify:

```bash
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -c "\dt public.*"
psql -h localhost -p 5432 -U postgres -d Seoul_Transit -c "SELECT PostGIS_Version();"
```

Expected tables — 18 in total:

```text
admin_dong_boundary
analysis_table_final
anomaly_data
bus_stop_demand_node_mapping
bus_stop_location
dow_hourly_ratio
dow_hourly_stop_pattern
estimated_hourly_od
estimated_hourly_stop_demand
holiday_config
hourly_bus_stop_passenger
integrated_bus_stop_location
integrated_hourly_transit_demand_light
month_day_count
subway_hourly_station_demand_light
subway_station_join_alias
subway_station_location
spatial_ref_sys
```

`spatial_ref_sys` is created by the PostGIS extension, not by this project.

At this point the API server will start. Continue to section 4 if you need the
endpoints to return data.

## 4. Load reference data

Order matters — later steps join against earlier ones.

### 4.0 Source files

The reference CSVs are **not committed to the repository** — they are excluded
by `.gitignore`. Obtain them before running the loaders.

| File | Expected path | Source |
|---|---|---|
| Bus stop master | `data/reference/seoul_bus_stop_master.csv` | Seoul Open Data Plaza — bus stop master (정류장 마스터) |
| Subway station master | `data/reference/서울시 역사마스터 정보.csv` | Seoul Open Data Plaza — station master (역사마스터 정보) |
| Curated stop mapping | `data/processed/bus_stop_location/*.csv` | Produced by `pipelines/bus_stop_integration/` |

Required source columns:

```text
bus stop master     : 정류장_ID, 정류장_번호, 정류장_명칭, 경도, 위도   (cp949)
subway station master: 역사_ID, 역사명, 호선, 위도, 경도               (cp949)
```

The loaders fail with a clear error if a required column is missing, so a
different export of the same dataset can be adapted by renaming columns.

### 4.1 Bus stop master

This loader takes command-line arguments:

```bash
python pipelines/reference_loader/load_bus_stop_location.py \
  --csv data/reference/seoul_bus_stop_master.csv \
  --db-url "postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit" \
  --replace
```

Then populate the geometry column, which the loader does not set:

```sql
UPDATE public.bus_stop_location
SET geom = ST_SetSRID(ST_MakePoint(경도, 위도), 4326)
WHERE 경도 IS NOT NULL AND 위도 IS NOT NULL;
```

### 4.2 Subway station master, then aliases

This loader takes **no** command-line arguments. It reads configuration from
the environment:

```bash
export PIPELINE_DB_URL='postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'
python pipelines/metro_ingestion/load_subway_station_location.py
```

PowerShell:

```powershell
$env:PIPELINE_DB_URL='postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'
python pipelines/metro_ingestion/load_subway_station_location.py
```

Set `SUBWAY_STATION_CSV` to override the default input path.

Expected row count after loading:

```sql
SELECT COUNT(*) FROM subway_station_location;  -- 783
```

The alias script runs an `UPDATE` against `subway_station_location`, so apply
it only after the station rows exist:

```bash
psql -h localhost -p 5432 -U postgres -d Seoul_Transit \
  -f services/api-server/src/main/resources/sql/subway_station_join_alias.sql
```

### 4.3 Administrative dong boundaries

There is no loader script for this table. Import the SGIS shapefile,
reprojecting from EPSG:5186 to EPSG:4326:

```bash
shp2pgsql -s 5186:4326 -I -W UTF-8 -g geom \
  BND_ADM_DONG_PG/BND_ADM_DONG_PG.shp public.admin_dong_boundary_import \
  | psql -h localhost -p 5432 -U postgres -d Seoul_Transit
```

```sql
INSERT INTO public.admin_dong_boundary (adm_cd, adm_nm, base_date, geom)
SELECT adm_cd, adm_nm, base_date, ST_Multi(geom)
FROM public.admin_dong_boundary_import
ON CONFLICT (adm_cd) DO UPDATE SET
    adm_nm    = EXCLUDED.adm_nm,
    base_date = EXCLUDED.base_date,
    geom      = EXCLUDED.geom;

DROP TABLE public.admin_dong_boundary_import;
```

Full loading options, including an `ogr2ogr` alternative using the GeoJSON
already in the repository, are documented in
`database/schema/04_reference_admin_dong_boundary.sql`.

Sanity check — the district code used in the smoke tests must resolve:

```sql
SELECT adm_cd, adm_nm FROM public.admin_dong_boundary WHERE adm_cd = '11230760';
```

### 4.4 Integrated bus stop mapping

The curated CSVs must exist under `data/processed/bus_stop_location/`. If they
do not, generate them first with `pipelines/bus_stop_integration/`.

Run from the repository root; the `\copy` paths inside the script are relative
to the working directory:

```bash
psql -h localhost -p 5432 -U postgres -d Seoul_Transit \
  -f scripts/db/load_curated_bus_stop_mapping.psql
```

This truncates and reloads `integrated_bus_stop_location` and
`bus_stop_demand_node_mapping`, and fills the geometry column.

## 5. Load demand data

Run the pipelines in phase order. Each has its own README with arguments.

| Order | Pipeline | Produces |
|---:|---|---|
| 1 | `pipelines/od_correction/` | `analysis_table_final`, `anomaly_data` |
| 2 | `pipelines/hourly_od_estimation/` | `hourly_bus_stop_passenger`, `month_day_count`, `dow_hourly_ratio`, `estimated_hourly_od` |
| 3 | `pipelines/hourly_stop_pattern/` | `dow_hourly_stop_pattern`, `estimated_hourly_stop_demand` |
| 4 | `pipelines/subway_integration_light/` | `subway_hourly_station_demand_light`, `integrated_hourly_transit_demand_light` |

`integrated_hourly_transit_demand_light` is the table both map endpoints read,
so step 4 is what makes `node-catchment` and `district-demand` return rows.

## 6. Refresh planner statistics

After loading data, re-run the performance step so the query planner has
current statistics:

```bash
psql -h localhost -p 5432 -U postgres -d Seoul_Transit \
  -f database/performance/phase6_9_2_district_demand_indexes.sql
```

Skipping this is the most likely cause of a district-demand call taking
several seconds on a freshly loaded database. The measurements in
`docs/performance/phase6_9_2_district_demand_optimization.md` were taken with
statistics refreshed.

## 7. Credentials

Pipeline scripts read the database URL from `PIPELINE_DB_URL` or accept it as a
`--db-url` argument. Do not hardcode passwords in scripts or in pipeline
READMEs. Set the variable per shell session:

```bash
export PIPELINE_DB_URL='postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'
```

The API server uses a separate variable, `DB_PASSWORD` — see
[`local_runbook.md`](./local_runbook.md).

## 8. Validation queries

```bash
psql ... -f pipelines/hourly_stop_pattern/validation_queries_phase6_3.sql
psql ... -f pipelines/subway_integration_light/validation_queries_phase6_5_light.sql
psql ... -f services/api-server/src/main/resources/sql/validate_integrated_bus_stop_coverage.sql
```

## Verification status

The schema in `database/schema/` has been run against an empty database and
compared column-by-column with the working `Seoul_Transit` database.

| Table | Status |
|---|---|
| `analysis_table_final` | Verified — types match; varchar lengths are 255 |
| `integrated_bus_stop_location` | Verified — columns, types, and primary key match |
| `admin_dong_boundary` | Verified — ogc_fid serial key, MultiPolygon geometry |
| `holiday_config` | Verified — 날짜 is DATE, second column is holiday_name |
| `bus_stop_location` | Not yet compared |
| `subway_station_location` | Not yet compared |
| Pipeline tables (6.2 / 6.3 / 6.5) | Defined by their own pipeline SQL files |

The remaining two are reference tables created by Python loaders; compare them
with:

```cmd
psql -U postgres -d Seoul_Transit -c "\d bus_stop_location"
psql -U postgres -d Seoul_Transit -c "\d subway_station_location"
```

## Troubleshooting

### Schema validation error on startup

```text
Schema-validation: missing table [analysis_table_final]
```

Section 3 was not run, or the API server is pointed at a different database
than the one you created. Check `DB_URL`.

To start the server against an incomplete schema for debugging only:

```
JPA_DDL_AUTO=none
```

Do not use this as a normal setting — it disables the check that catches
schema drift.

### Encoding error while running a schema file

```text
ERROR: character with byte sequence 0xa4 0x80 in encoding "UHC" has no
equivalent in encoding "UTF8"
```

The client encoding is not UTF8. See section 0. All schema files are
idempotent, so re-running after fixing the encoding is safe.

### Spatial function does not exist

```text
ERROR: function st_setsrid(geometry, integer) does not exist
```

PostGIS is not enabled in this database. Extensions are per-database, so
enabling it elsewhere does not help. Re-run `database/schema/00_extensions.sql`.

### Empty results from map endpoints

The endpoint returns HTTP 200 with an empty array rather than an error. This is
the structure-only state: tables exist but hold no rows. Complete sections 4
and 5.
