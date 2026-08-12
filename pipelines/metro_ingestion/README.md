# Metro Ingestion (지하철 데이터 적재)

> Korean comments are added in parentheses for review and handoff. (지하철 역사 마스터 CSV를 DB에 적재하는 로더 문서입니다.)

## Purpose (목적)

This package loads metro-related raw data into PostgreSQL.
(이 패키지는 지하철 관련 원천 데이터를 PostgreSQL에 적재한다.)

Current loader:

```text
load_subway_station_location.py
```

## Directory Structure (디렉터리 구조)

```text
SeoulTransitPlatform/
├─ pipelines/
│  └─ metro_ingestion/
│     ├─ __init__.py
│     ├─ load_subway_station_location.py
│     └─ README.md
└─ data/
   └─ reference/
      └─ 서울시 역사마스터 정보.csv
```

## Input CSV (입력 CSV)

```text
data/reference/서울시 역사마스터 정보.csv
```

Override with the `SUBWAY_STATION_CSV` environment variable if the file lives
elsewhere. This CSV is not committed to the repository; download it from the
Seoul Open Data Plaza station master dataset.

Encoding:

```text
cp949
```

Source columns:

```text
역사_ID
역사명
호선
위도
경도
```

## Output Table (출력 테이블)

```sql
CREATE TABLE IF NOT EXISTS subway_station_location (
    station_id VARCHAR(50),
    station_name VARCHAR(100),
    line_name VARCHAR(100),
    lat DOUBLE PRECISION,
    lng DOUBLE PRECISION,
    PRIMARY KEY (station_id)
);
```

## Column Mapping (컬럼 매핑)

```text
역사_ID -> station_id
역사명   -> station_name
호선     -> line_name
위도     -> lat
경도     -> lng
```

## Install Dependencies (의존성 설치)

```bash
pip install pandas sqlalchemy psycopg2-binary
```

## Run (실행)

Run from the project root:

Set the database URL first; it is read from the environment so that no
credential is stored in the repository.

```bash
export PIPELINE_DB_URL='postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'
python pipelines/metro_ingestion/load_subway_station_location.py
```

PowerShell:

```powershell
$env:PIPELINE_DB_URL='postgresql+psycopg2://postgres:PASSWORD@localhost:5432/Seoul_Transit'
python pipelines/metro_ingestion/load_subway_station_location.py
```

This script takes no command-line arguments.

## Verify (검증)

```sql
SELECT COUNT(*)
FROM subway_station_location;
```

Expected result:

```text
783
```

## Join Test (조인 테스트)

```sql
SELECT
    d.mode,
    d.service_id,
    d.node_name,
    s.lat,
    s.lng,
    d.boarding,
    d.alighting
FROM integrated_hourly_transit_demand_light d
JOIN subway_station_location s
  ON d.service_id = s.line_name
 AND d.node_name = s.station_name
WHERE d.mode = 'subway'
LIMIT 100;
```
