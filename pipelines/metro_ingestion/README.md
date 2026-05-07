# Metro Ingestion

## Purpose

This package loads metro-related raw data into PostgreSQL.
(이 패키지는 지하철 관련 원천 데이터를 PostgreSQL에 적재한다.)

Current loader:

```text
load_subway_station_location.py
```

## Directory Structure

```text
SeoulTransitPlatform/
├─ pipelines/
│  └─ metro_ingestion/
│     ├─ __init__.py
│     ├─ load_subway_station_location.py
│     └─ README.md
└─ data/
   └─ raw/
      └─ subway/
         └─ 서울시 역사마스터 정보.csv
```

## Input CSV

```text
data/raw/subway/서울시 역사마스터 정보.csv
```

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

## Output Table

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

## Column Mapping

```text
역사_ID -> station_id
역사명   -> station_name
호선     -> line_name
위도     -> lat
경도     -> lng
```

## Install Dependencies

```bash
pip install pandas sqlalchemy psycopg2-binary
```

## Run

Run from the project root:

```bash
cd SeoulTransitPlatform
python pipelines/metro_ingestion/load_subway_station_location.py
```

## Verify

```sql
SELECT COUNT(*)
FROM subway_station_location;
```

Expected result:

```text
783
```

## Join Test

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
