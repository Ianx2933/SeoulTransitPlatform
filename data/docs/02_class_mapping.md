# 02. Class Mapping

## Goal

Map existing classes to new package locations and names.

This document is the main reference during refactoring.

---

## A. Existing BusStop Layer to New Structure

| Existing Class | New Package | New Class |
|---|---|---|
| `BusStopController` | `odcorrection/api` | `OdCorrectionController` |
| `BusStopService` | `odcorrection/application` | `OdCorrectionOrchestrator` |
| `BusStopRepository` | `odcorrection/infrastructure` | `OdCorrectionCommandRepository` |
| `BusStop` | `curatedod/infrastructure` | `CuratedOdRecord` |
| `BusStopId` | `curatedod/infrastructure` | `CuratedOdRecordId` |

---

## Important Interpretation

`BusStop` is closer to the final curated OD read model than to operational correction logic.

So it should move to `curatedod`, not remain under correction logic.

---

## B. BusStopService Method Split

| Existing Method | New Class |
|---|---|
| `fixVirtualStop()` | `OdCorrectionOrchestrator` |
| `fixSequence()` | `OdCorrectionOrchestrator` |
| `fixSameStopOD()` | `OdCorrectionOrchestrator` |
| `getNullStandardCode()` | `CorrectionQueryService` |
| `getNullAlightingStandardCode()` | `CorrectionQueryService` |
| `detectBidirectionalRoutes()` | `CorrectionQueryService` |
| `getStandardCodeByArs()` | `SeoulBusApiClient` |
| `deduplicateAndSum()` | `DeduplicationService` |
| `fixNullStandardCodes()` | `StandardCodeCorrectionService` |
| `fixNullAlightingStandardCodes()` | `StandardCodeCorrectionService` |
| `fixVirtualStopArs()` | `VirtualStopCorrectionService` |
| `fixBoardingSequence()` | `VirtualStopCorrectionService` |
| `fixAlightingSequence()` | `VirtualStopCorrectionService` |
| `fixEmptyArrivalStopName()` | `VirtualStopCorrectionService` |
| `fixVirtualStopBoarding()` | `VirtualStopCorrectionService` |
| `fixMidVirtualStopBoarding()` | `VirtualStopCorrectionService` |
| `fixVirtualStopSameOD()` | `VirtualStopCorrectionService` |
| `fixSequenceByArs()` | `SequenceCorrectionService` |
| `fixAlightingSequenceByArs()` | `SequenceCorrectionService` |
| `fixSameStopODBySequence()` | `SameStopCorrectionService` |
| `fixSameStopODByArs()` | `SameStopCorrectionService` |

---

## C. Repository Split

| Existing Responsibility | New Repository |
|---|---|
| query / select / count | `OdCorrectionQueryRepository` |
| update / command / anomaly insert-delete | `OdCorrectionCommandRepository` |
| long SQL / temp table / direct JDBC | `OdCorrectionJdbcRepository` |

---

## D. CuratedOD Skeleton

| Responsibility | New Class |
|---|---|
| curated OD API | `CuratedOdController` |
| curated OD query orchestration | `CuratedOdQueryService` |
| curated OD query SQL | `CuratedOdQueryRepository` |
| analysis_table_final read model | `CuratedOdRecord` |
| composite key | `CuratedOdRecordId` |

---

## E. Congestion Skeleton

| Responsibility | New Class |
|---|---|
| congestion API | `CongestionController` |
| congestion query orchestration | `CongestionQueryService` |
| occupancy calculation | `OccupancyCalculator` |
| congestion SQL | `CongestionQueryRepository` |

---

## F. Prediction Skeleton

| Responsibility | New Class |
|---|---|
| prediction API | `PredictionController` |
| prediction business logic | `PredictionService` |
| external flask/prediction integration | `PredictionClient` |

---

## G. Common Layer

| Responsibility | New Class |
|---|---|
| Seoul bus external API | `SeoulBusApiClient` |
| rest client config | `RestClientConfig` |
| cache config | `CacheConfig` |
| openapi config | `OpenApiConfig` |
| global exception handling | `GlobalExceptionHandler` |
| error response | `ErrorResponse` |
