# 01. Target Architecture

## Goal

Refactor the current Spring application into domain-oriented packages while keeping a familiar Spring structure:

- Controller
- Service
- Repository
- Entity

The internal implementation will be split more clearly by responsibility.

---

## Top-Level Project Structure

```Text
SeoulTransitPlatform/
├── services/
│   ├── api-server/
│   └── prediction-service/
├── pipelines/
│   └── od_correction/
├── data/
└── docs/
    └── refactoring/
```

---

## Spring Domain Structure

```Text
services/api-server/src/main/java/.../
├── odcorrection/
├── curatedod/
├── congestion/
├── prediction/
└── common/
```

---

## Domain Responsibilities

### odcorrection
Operational correction logic and admin APIs.

Includes:
- correction orchestration
- virtual stop correction
- sequence correction
- same-stop OD correction
- standard code correction
- deduplication
- correction query/admin logic

### curatedod
Read model and serving layer for curated OD data.

Includes:
- curated OD controller
- curated OD query service
- curated OD query repository
- curated OD record/read model

### congestion
Serving and calculation logic for congestion metrics.

Includes:
- congestion query service
- occupancy calculator
- congestion query repository

### prediction
Prediction-facing API and external prediction integration.

Includes:
- prediction controller
- prediction service
- prediction client
- DTOs for request/response

### common
Shared infrastructure.

Includes:
- API clients
- config
- exception handling
- cache/openapi config

---

## Package-Level Skeleton

### odcorrection

```Text
odcorrection/
├── api/
│   └── OdCorrectionController.java
├── application/
│   ├── OdCorrectionOrchestrator.java
│   ├── VirtualStopCorrectionService.java
│   ├── SequenceCorrectionService.java
│   ├── SameStopCorrectionService.java
│   ├── StandardCodeCorrectionService.java
│   ├── DeduplicationService.java
│   └── CorrectionQueryService.java
├── infrastructure/
│   ├── OdCorrectionCommandRepository.java
│   ├── OdCorrectionQueryRepository.java
│   └── OdCorrectionJdbcRepository.java
├── dto/
└── model/
```

### curatedod

```Text
curatedod/
├── api/
│   └── CuratedOdController.java
├── application/
│   └── CuratedOdQueryService.java
├── infrastructure/
│   ├── CuratedOdQueryRepository.java
│   ├── CuratedOdRecord.java
│   └── CuratedOdRecordId.java
├── dto/
│   ├── CuratedOdResponse.java
│   └── StopOdMatrixResponse.java
└── model/
```

### congestion

```Text
congestion/
├── api/
│   └── CongestionController.java
├── application/
│   ├── CongestionQueryService.java
│   └── OccupancyCalculator.java
├── infrastructure/
│   └── CongestionQueryRepository.java
├── dto/
│   ├── CongestionResponse.java
│   ├── RouteCongestionResponse.java
│   └── SectionCongestionResponse.java
└── model/
```

### prediction

```Text
prediction/
├── api/
│   └── PredictionController.java
├── application/
│   └── PredictionService.java
├── client/
│   └── PredictionClient.java
├── dto/
│   ├── PredictionRequest.java
│   ├── HourlyPredictionResponse.java
│   └── WeightUpdateRequest.java
└── model/
```

### common

```text
common/
├── config/
│   ├── RestClientConfig.java
│   ├── CacheConfig.java
│   └── OpenApiConfig.java
├── exception/
│   ├── GlobalExceptionHandler.java
│   └── ErrorResponse.java
└── client/
    └── SeoulBusApiClient.java
```

---

## Architecture Principles

1. Keep the external Spring structure familiar.
2. Split internal responsibilities by domain and correction rule.
3. Treat `curatedod` as the final read/serving model.
4. Keep Python responsible for preprocessing and 1st-stage correction.
5. Keep Spring responsible for operational/admin correction and serving.
6. Separate query, command, and JDBC-heavy repository responsibilities.
