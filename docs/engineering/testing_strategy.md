# Testing strategy

The test suite is designed around **engineering invariants**, not a coverage target.
The highest-value tests encode the decisions that can silently corrupt a mobility
pipeline when they regress.

## Python contract tests

`pytest` covers the ordered bus-stop coordinate matcher, OD identifier-recovery
helpers, model-independent prediction-service behavior, and a source-level
compile guard for the Airflow DAG. Flask HTTP tests inject a stub predictor so
CI does not require private binary model artifacts.

The key contracts are:

- stronger matching stages win over weaker fallbacks;
- ARS-only fallback is allowed only when the coordinate candidate is unique;
- each resolved coordinate row records the actual `match_method` and
  `match_confidence` used;
- ambiguous key-to-code mappings are not resolved by selecting an arbitrary
  first value;
- fallback joins preserve record cardinality;
- malformed or implausible coordinates are rejected before they reach the
  mapping output;
- known source-format defects, including spreadsheet-corrupted route labels and
  identifier padding, remain reproducibly normalized;
- hourly prediction expansion preserves the daily boarding and alighting totals;
- Flask 400/501 response behavior is testable without loading a model artifact;
- the committed Airflow DAG source always compiles.

Run from the repository root:

```bash
python -m pip install -r requirements-test.txt
pytest -q
```

CI intentionally does **not** publish a coverage percentage. The suite is
reviewed by named behavioral contracts and failure modes; a low or high line
coverage number would not distinguish safe ambiguity handling from trivial
execution. Coverage can still be measured ad hoc during development (install `pytest-cov`
separately when needed), but it is not a portfolio KPI or CI gate.

## PostGIS integration tests

`DistrictDemandRepositoryPostgisTest` uses the shared Spring Boot
`TestcontainersConfiguration`, which provides one real
`postgis/postgis:16-3.4` database (plus Redis) for the integration-test context.
The repository test injects the same `JdbcTemplate` connection rather than
starting a second database container.

The fixture includes a stop exactly on an administrative polygon boundary. The
repository uses `ST_Covers`, so the boundary stop must be included while an
outside stop is excluded. This prevents an accidental change to `ST_Contains`
from silently dropping boundary points.

The same integration test also verifies that `nodeLimit` truncates only the
returned node list and does not alter full-dataset aggregate totals.

Docker is required for the PostGIS test. Testcontainers starts and disposes the
database automatically.

## Existing backend and frontend tests

The backend suite also covers service-layer request parsing/validation and the
stateless occupancy rules, in addition to JUnit tests for SQL helpers, distance
calculations, and node-id normalization. Controller-slice tests lock down HTTP
400/404 error contracts. Vitest covers frontend demand sorting.

## Continuous integration

`.github/workflows/ci.yml` runs three independent jobs on pushes and pull
requests:

1. Python pipeline and prediction-service contract tests;
2. Spring Boot/JUnit tests against Testcontainers PostGIS and Redis;
3. React/Vitest frontend tests.

The separation makes failures attributable to one layer and gives reviewers a
fast signal that the repository is tested across ingestion, spatial querying,
and presentation logic.
