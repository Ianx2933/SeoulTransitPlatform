# 03. Refactoring Order

## Goal

Define the execution order for refactoring.

The rule is:

> Fix naming, boundaries, and structure first.
> Move logic only after the destination structure is ready.

---

## Phase 1. Documentation First

Create and freeze the refactoring documents:

- `01_target_architecture.md`
- `02_class_mapping.md`
- `03_refactoring_order.md`
- `04_python_pipeline_notes.md`

Why:
- Prevent naming drift during implementation
- Keep package boundaries stable
- Give a single source of truth for class movement

---

## Phase 2. Project Skeleton

Create top-level folders:

- `services/api-server`
- `services/prediction-service`
- `pipelines/od_correction`
- `docs/refactoring`

Create Spring package skeletons:

- `odcorrection`
- `curatedod`
- `congestion`
- `prediction`
- `common`

Why:
- Classes need a stable destination before logic is moved

---

## Phase 3. Freeze Python Baseline

Confirm and fix the Python baseline:

- `od_correction_pipeline.py`
- inspection script location
- archive policy for experimental scripts
- curated CSV output contract

Why:
- Spring refactoring must assume a stable Python output

---

## Phase 4. Rebuild ODCorrection First

Create empty classes first:

- `OdCorrectionController`
- `OdCorrectionOrchestrator`
- `CorrectionQueryService`
- `StandardCodeCorrectionService`
- `VirtualStopCorrectionService`
- `SequenceCorrectionService`
- `SameStopCorrectionService`
- `DeduplicationService`
- `OdCorrectionQueryRepository`
- `OdCorrectionCommandRepository`
- `OdCorrectionJdbcRepository`
- `SeoulBusApiClient`

Why:
- This is the most tangled area
- It becomes the template for the rest of the refactoring

---

## Phase 5. Split BusStopService

Move logic from `BusStopService` in this order:

1. `CorrectionQueryService`
2. `StandardCodeCorrectionService`
3. `VirtualStopCorrectionService`
4. `SequenceCorrectionService`
5. `SameStopCorrectionService`
6. `DeduplicationService`
7. orchestrate call order in `OdCorrectionOrchestrator`

Why:
- Query and standard-code logic are the easiest to isolate first
- Sequence / same-stop / deduplication depend more strongly on ordering

---

## Phase 6. Split Repositories

Move repository responsibilities in this order:

1. read/query logic to `OdCorrectionQueryRepository`
2. update/command logic to `OdCorrectionCommandRepository`
3. long SQL / JDBC-heavy logic to `OdCorrectionJdbcRepository`

Why:
- Service separation is incomplete if repositories remain mixed

---

## Phase 7. Rebuild CuratedOD

Create in this order:

1. `CuratedOdRecord`
2. `CuratedOdRecordId`
3. `CuratedOdQueryRepository`
4. `CuratedOdQueryService`
5. `CuratedOdController`

Why:
- CuratedOD is simpler and can follow the ODCorrection pattern

---

## Phase 8. Rebuild Congestion

Create in this order:

1. `CongestionQueryRepository`
2. `OccupancyCalculator`
3. `CongestionQueryService`
4. `CongestionController`

Why:
- Split SQL and calculator logic cleanly

---

## Phase 9. Rebuild Prediction

Create in this order:

1. DTO cleanup
2. `PredictionClient`
3. `PredictionService`
4. `PredictionController`

Why:
- Prediction depends on external integration and should come after the main API structure stabilizes

---

## Phase 10. Rebuild Common

Create/refine:

- `SeoulBusApiClient`
- `RestClientConfig`
- `GlobalExceptionHandler`
- `ErrorResponse`
- `CacheConfig`
- `OpenApiConfig`

Why:
- Common infrastructure should be stabilized after domain packages are laid out

---

## Phase 11. Operational Quality Improvements

Apply after the structure is stable:

- logging
- exception handling refinement
- tests
- Redis
- cloud deployment settings

Why:
- Quality improvements should not be repeatedly rewritten while structure is still moving

---

## Immediate Execution Checklist

1. Create `docs/refactoring` markdown files
2. Create Spring package skeleton
3. Apply renaming:
   - `BusStop -> ODCorrection`
   - `OD -> CuratedOD`
4. Create empty ODCorrection classes
5. Annotate where each old `BusStopService` method will move
6. Move services
7. Move repositories