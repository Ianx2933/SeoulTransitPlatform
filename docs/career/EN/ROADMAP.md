# SeoulTransitPlatform — Product & Engineering Roadmap

> As of October 2026 · Product and engineering priorities for moving from Closed Beta toward broader Public Beta access.  
> Principle: Prioritize **data reliability → independent user value → sustainable operations** over adding features.

## North Star: Public Mobility Data Product

Enable external users to explore transit demand by area, line, and time period; identify the data source, analytical assumptions, and whether a figure is **observed, corrected, estimated, or forecast**; and obtain a reproducible **evidence document or dataset**.

## P0 · Safety and accuracy (before expanding Public Beta)

- [ ] Audit and rotate operational API keys/credentials where needed; mask sensitive values in logs.
- [ ] Verify least-privilege access for Cloud Run, Cloud SQL, and job service accounts; review Secret Manager coverage.
- [ ] Identify missing Cloud SQL triggers and verify any data impact.
- [ ] Automate source-to-aggregate tests for missing/duplicate rows, preserved totals, and hourly allocations; prevent publication when critical checks fail.
- [ ] Review source-data licensing, redistribution permissions, and attribution requirements.
- [ ] Check official OSM tile policy, required attribution, HTTP caching, and fallback options if usage grows.

**Definition of done:** New aggregates that fail critical integrity checks are not published, and known high-priority issues involving credentials and access rights are resolved.

## P1 · Reliable analytical data mart

- [ ] Define value types (observed/corrected/estimated/forecast) and dataset release identifiers.
- [ ] Replace BigQuery's **outdated six-month load** with a validated rebuild covering all 32 months.
- [ ] Reconcile PostgreSQL and BigQuery metric definitions, totals, periods, and administrative-boundary versions.
- [ ] Version administrative boundaries and stop-to-neighborhood assignments.
- [ ] Define and implement the **grain, keys, time level, and missing/corrected-value handling** for `fact_od_dong`.
- [ ] Add useful descriptive statistics (mean, median, range/variation, sample count) to frequently used aggregates.

**Definition of done:** The same metric can be reproduced across service and warehouse using a specified data release, with the observed, corrected, and estimated portions of neighborhood OD demand clearly identified.

## P2 · Deliver usable outputs to users

- [ ] Publish a common data dictionary, interpretation notes, data refresh date, and correction history.
- [ ] Select **one complete user scenario**, for example comparing neighborhood demand by chosen dates and time windows.
- [ ] Deliver one citizen-facing evidence document in HTML/print/PDF format, including period, metrics, source, version, method, and limitations.
- [ ] Provide CSV/Parquet downloads for researchers with schema, selected filters, metric definitions, and usage terms.
- [ ] Test the full exploration → validation → document/download flow with external users.

**Definition of done:** An external user can complete one validated transit-demand analysis and retrieve supporting evidence without the developer's intervention.

## P3 · Orchestration and operational reliability

- [ ] Inventory the current local Airflow DAGs, dependencies, retry rules, and backfill needs.
- [ ] Choose a cloud deployment approach for Airflow and evaluate any always-on infrastructure costs.
- [ ] Assign ownership of each schedule to either Airflow or Cloud Scheduler and **prevent duplicate runs**.
- [ ] Decide whether Cloud Run Jobs should remain the execution layer for batch computations, separate from DAG orchestration.
- [ ] Test idempotent loads, retries, failure notifications, checkpoints, and historical reprocessing.
- [ ] Continue using existing GCP budget alerts and review actual monthly spend by service.
- [ ] Strengthen failure handling and critical data-quality tests in existing GitHub Actions push/PR CI (pytest, JUnit, Testcontainers, Vitest).
- [ ] Inspect Caffeine cache hits/misses, TTL, invalidation on data releases, and behavior across multiple instances.

**Definition of done:** Failed monthly collection steps can be identified and selectively rerun, while published data updates avoid duplicates and omissions.

## P4 · Verified analytical extensions

- [ ] Develop reusable segment-load fact/aggregate views, clearly distinguishing observed loads from OD reconstruction or route-assignment estimates.
- [ ] Ensure DEM-based terrain attributes are not misrepresented as field-surveyed pedestrian accessibility measurements.
- [ ] Evaluate XGBoost with temporal holdouts, simple baselines, error breakdowns, and uncertainty reporting.
- [ ] Collect genuine external usage cases and operating history before deciding on a wider Public Beta release.

**Execution principle:** P4 should not delay the P0/P1/P2 core product flow. Prioritize extensions only when their domain value is clear.

## Public Beta expansion: Go / No-go

- **Trust:** Can users identify sources, data versions, estimates, and material limitations?
- **Safety:** Are high-priority secrets, access, licensing, and map-tile policy concerns addressed?
- **Reproducibility:** Can results be regenerated under the same conditions?
- **User value:** Has an external user completed at least one end-to-end query and evidence/document/download flow?
- **Operations:** Can ingestion failures be detected and rerun while preserving previously validated public data?

## Deferred or out-of-scope work

- Technology replacement solely to introduce a new language, map engine, or caching server.
- Promoting predictions as the product's headline capability before model validation.
- Completing every segment-load and advanced analytics feature before Public Beta.
- Starting another large side project.

## Outstanding decisions

1. Airflow deployment topology and division of responsibilities with Cloud Scheduler.
2. Batching, validation, and failure recovery strategy for the full 32-month BigQuery reload.
3. Metrics, user interface, and report scope for the first complete external-use scenario.
4. Production Caffeine/Cloud Run latency metrics and operational cost targets.
