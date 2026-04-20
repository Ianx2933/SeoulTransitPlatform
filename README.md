# SeoulTransitPlatform

**1. Overview**
**2. Architecture**
**3. Data Pipeline**
**4. Core Features**
**5. Tech Stack**
**6. System Design Decisions**
**7. API Examples**
**8. Prediction System**
**9. Data Quality & Correction Strategy**
**10. Deployment (GCP)**
**11. Future Work**

## 1. Overview
SeoulTransitPlatform is a geospatial data platform designed to analyze urban mobility patterns in Seoul Metropolitan Area.

This project aims to address the limitations of raw transit data by reconstructing reliable OD flows and enabling scalable mobility analytics.

The system processes large-scale transit smart card data, reconstructs origin-destination (OD) flows, and provides:

- Bus congestion analytics
- OD flow exploration APIs
- Time-series passenger prediction
- Geospatial visualization outputs

The platform is designed with a clear separation between data pipelines and serving APIs.

**Problem Statement**

Raw transit data contains inconsistencies such as:
- Missing route identifiers
- Inconsistent stop codes
- Incomplete OD records

This platform focuses on reconstructing reliable OD flows from imperfect data.

**Data Scale**

- Daily transactions: ~10M records
- Raw data size: ~2TB/day
- OD dataset processed for query optimization

## 2. Architecture
1. Data Pipeline (Airflow + Python)
   - Raw data ingestion
   - OD reconstruction
   - Multi-stage data correction
   - Data validation
   - Curated dataset generation

2. API Layer (Spring Boot)
   - Congestion API
   - OD query API
   - Prediction API
   - Redis-based caching layer

   The API layer serves only validated and curated data.

3. Prediction Service (Flask)
   - XGBoost-based daily prediction
   - Hourly distribution modeling
   - Dynamic weighting system

**Architecture Diagram**
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
    │     Airflow Pipeline │                 │   Model Artifacts     │
    │  - Data Ingestion    │                 │  (Cloud Storage)      │
    │  - OD Reconstruction │                 └──────────────────────┘
    │  - Data Correction   │
    │  - Validation        │
    └──────────────────────┘
```

## 3. Data Pipeline
The pipeline processes raw BMS and OD datasets into a curated analysis-ready dataset.

**Steps**:
1. Raw Data Ingestion
2. Schema Normalization
3. Route ID Mapping
4. Standard Code Mapping
5. Multi-stage Data Correction:
   - Direct mapping (route + sequence)
   - ARS-based fallback
   - Stop name fallback
   - Cross-reference correction (boarding ↔ alighting)
6. Data Validation
7. Curated Data Output

## 4. Core Features
- Bus congestion estimation based on reconstructed passenger flow
- OD flow exploration per route and stop
- Section-based congestion analysis
- Time-series passenger prediction (daily + hourly)
- Interactive geospatial visualization using Leaflet
- Internal analysis and validation using Folium

## 5. Tech Stack
- Python (Pandas, ETL processing)
- Airflow (Pipeline orchestration)
- PostgreSQL / PostGIS
- Spring Boot (API server)
- Redis (Caching)
- Flask (Prediction service)
- Leaflet (Frontend visualization)
- GCP (Cloud Run, Cloud SQL, Cloud Storage)

## 6. System Design Decisions
- **Separation of concerns**:
  - Data correction is handled in Airflow pipelines
  - APIs serve only validated curated data

- **SQL-first approach**:
  Complex OD and congestion calculations are implemented using SQL (CTE and window functions)

- **Multi-stage fallback correction strategy**:
  Improves data completeness while maintaining traceability

- **Prediction as a microservice**:
  Model inference is decoupled from the API layer for scalability

- **Caching strategy**:
  Route-level congestion results are cached using Redis with TTL policies

## 7. API Examples

GET /api/congestion/{route}?date=YYYYMMDD

GET /api/od/{route}/{ars}?date=YYYYMMDD

GET /api/prediction/hourly?routeNo={route}&arsNo={ars}&date=YYYYMMDD

## 8. Prediction System

The prediction system is designed as a separate microservice.

- **Spring Boot**:
  - Feature engineering (day type, lag features)
  - API orchestration

- **Flask**:
  - XGBoost model inference
  - Hourly distribution modeling
  - Dynamic weighting adjustments

**Model features**:
- Day type (weekday / weekend / holiday)
- Month
- Previous week passenger count
- Previous month passenger count

## 9. Data Quality & Correction Strategy

To improve data quality, a multi-stage correction pipeline is applied:

1. Direct mapping (route + sequence)
2. ARS-based fallback
3. Stop name-based fallback
4. Cross-reference correction (boarding ↔ alighting)

Each correction stage is tracked using metadata:
- source
- confidence
This approach improves reconstruction accuracy while ensuring traceability.

## 10. Deployment (GCP)

The system is designed for deployment on GCP:

- **Cloud Run**:
  - API Server
  - Prediction Service

- **Cloud SQL**:
  - PostgreSQL / PostGIS

- **Cloud Storage**:
  - Raw data and model artifacts

- **Artifact Registry**:
  - Container images

- **Secret Manager**:
  - Credentials and API keys
 
 **Deployment Diagram**
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
## Sample Output

GET /api/congestion/143

[
  {
    "stopName": "Gangnam Station",
    "congestionLevel": "High",
    "passengers": 1200
  }
]

## 11. Future Work
- Real-time pipeline using Pub/Sub
- Streaming-based congestion updates
- Interactive frontend dashboard
- Automated model retraining
- BigQuery integration for large-scale analytics
