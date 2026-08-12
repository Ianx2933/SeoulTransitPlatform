# Architecture Overview


This document holds the project background, system design, and pipeline
description that previously lived in the repository README.  
The README now covers how to run the project; this document covers what it is and why it is
built this way.

## 1. Problem statement

SeoulTransitPlatform is a geospatial data platform for analyzing urban mobility
patterns in the Seoul metropolitan area.

Raw transit smart card data contains inconsistencies:

- missing route identifiers;
- inconsistent stop codes;
- incomplete OD records.

The platform focuses on reconstructing reliable origin-destination (OD) flows
from this imperfect data, and then serving analytics on top of the corrected
dataset.

Data scale:

| Property | Value |
|---|---|
| Daily transactions | approximately 10M records |
| Raw data size | approximately 2 TB/day |

## 2. System architecture

### 2.1 Layers

**Data pipeline (Airflow + Python)**
Raw data ingestion, OD reconstruction, multi-stage correction, validation, and
curated dataset generation.

**API layer (Spring Boot)**
Congestion API, OD query API, map demand APIs, prediction orchestration, and a
cache layer. The API layer serves only validated and curated data.

**Prediction service (Flask)**
XGBoost-based daily prediction, hourly distribution modeling, and dynamic
weighting.

### 2.2 Diagram

```text
                ┌────────────────────────────┐
                │        Frontend (Leaflet)  │
                │  - Interactive Map         │
                │  - API Calls               │
                └───────────────┬────────────┘
                                │
                                ▼
                ┌────────────────────────────┐
                │     Spring Boot API        │
                │  - Congestion API          │
                │  - OD API                  │
                │  - Map demand API          │
                │  - Prediction API          │
                │  - Redis Cache             │
                └───────────────┬────────────┘
                                │
         ┌──────────────────────┴──────────────────────┐
         ▼                                             ▼
┌──────────────────────┐                  ┌──────────────────────┐
│   PostgreSQL/PostGIS │                  │  Prediction Service  │
│  - Curated OD Data   │                  │     (Flask)          │
│  - Spatial Queries   │                  │  - XGBoost Model     │
└───────────┬──────────┘                  │  - Hourly Ratio      │
            │                             └───────────┬──────────┘
            │                                         │
            ▼                                         ▼
    ┌──────────────────────┐                 ┌──────────────────────┐
    │     Airflow Pipeline │                 │   Model Artifacts    │
    │  - Data Ingestion    │                 │  (Cloud Storage)     │
    │  - OD Reconstruction │                 └──────────────────────┘
    │  - Data Correction   │
    │  - Validation        │
    └──────────────────────┘
```

## 3. Data pipeline

The pipeline processes raw BMS and OD datasets into a curated,
analysis-ready dataset.

1. Raw data ingestion
2. Schema normalization
3. Route ID mapping
4. Standard code mapping
5. Multi-stage data correction
6. Data validation
7. Curated data output

The physical tables each pipeline stage produces, and the order they must run
in, are documented in [`docs/deployment/database_setup.md`](../deployment/database_setup.md).

## 3.1 Core features

- Bus congestion estimation based on reconstructed passenger flow
- OD flow exploration per route and stop
- Section-based congestion analysis
- Time-series passenger prediction (daily and hourly)
- Interactive geospatial visualization using Leaflet
- Internal analysis and validation using Folium

## 4. Data quality and correction strategy

Correction is applied in stages, each used as a fallback for the previous one:

| Stage | Method |
|---:|---|
| 1 | Direct mapping (route + sequence) |
| 2 | ARS-based fallback |
| 3 | Stop name-based fallback |
| 4 | Cross-reference correction (boarding ↔ alighting) |

Each stage records metadata — `source` and `confidence` — so that a corrected
record remains traceable back to the rule that produced it. This is the reason
`bus_stop_demand_node_mapping` carries `match_method` and `match_confidence`
columns.

## 5. System design decisions

**Separation of concerns.** Data correction happens in the pipelines. The API
serves only validated curated data. This keeps request-time logic simple and
makes correction reproducible offline.

**SQL-first.** Complex OD and congestion calculations are implemented in SQL
using CTEs and window functions rather than in application code, so that the
work happens where the data is.

**Multi-stage fallback correction.** Improves completeness without discarding
traceability.

**Prediction as a microservice.** Model inference is decoupled from the API
layer so the two can scale and deploy independently.

**Caching strategy.** Route-level congestion and district demand results are
cached with TTL policies. Provider selection and rationale are documented in
[`cache_profiles.md`](./cache_profiles.md).

## 6. Prediction system

| Component | Responsibility |
|---|---|
| Spring Boot | Feature engineering (day type, lag features), API orchestration |
| Flask | XGBoost inference, hourly distribution modeling, dynamic weighting |

Model features:

- day type (weekday / weekend / holiday)
- month
- previous week passenger count
- previous month passenger count

Holiday classification reads the `holiday_config` table.

## 7. API surface

```text
GET /api/congestion/{route}?date=YYYYMMDD
GET /api/od/{route}/{ars}?date=YYYYMMDD
GET /api/prediction/hourly?routeNo={route}&arsNo={ars}&date=YYYYMMDD
GET /api/map/node-catchment?lat=&lng=&radiusMeters=&modes=&dayTypes=&dayAggregation=&hours=
GET /api/map/district-demand?districtCode=&modes=&dayTypes=&dayAggregation=&hours=&nodeLimit=
```

Sample congestion response:

```json
[
  {
    "stopName": "Gangnam Station",
    "congestionLevel": "High",
    "passengers": 1200
  }
]
```

Runnable request examples with expected results are in
[`docs/deployment/smoke_tests.md`](../deployment/smoke_tests.md).

## 8. Target deployment (GCP)

Planned for Phase 7. Not yet implemented — see the phase status table in the
README.

| GCP service | Role |
|---|---|
| Cloud Run | API server, prediction service |
| Cloud SQL | PostgreSQL / PostGIS |
| Cloud Storage | Raw data and model artifacts |
| Artifact Registry | Container images |
| Secret Manager | Credentials and API keys |

```text
[Cloud Storage] → raw data / model
        ↓
[Airflow / Composer]
        ↓
[Cloud SQL (PostgreSQL + PostGIS)]
        ↓
[Cloud Run - Spring API]
        ↓
[Cloud Run - Prediction Service]
        ↓
[Leaflet Frontend (Static Hosting)]
```

Known gaps before this is buildable:

- no Dockerfile for `services/api-server` or `services/web-client`
  (only `services/prediction-service` has one);
- `docker-compose.yaml` provides Redis only, not PostgreSQL/PostGIS;
- `infra/` is a placeholder.

## 9. Future work

- Real-time pipeline using Pub/Sub
- Streaming-based congestion updates
- Automated model retraining
- BigQuery integration for large-scale analytics
- Precomputed `node_admin_dong_mapping` if district-demand latency regresses at
  larger scope (see
  [`docs/performance/phase6_9_2_district_demand_optimization.md`](../performance/phase6_9_2_district_demand_optimization.md))
