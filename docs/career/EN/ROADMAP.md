# SeoulTransitPlatform — Product & Engineering Roadmap

> As of November 2026 · Product and engineering priorities for moving from Closed Beta toward broader Public Beta access.  
> Principle: Prioritize **data reliability → independent user value → sustainable operations** over adding features.

## North Star: Public Mobility Data Product

Enable external users to explore transit demand by area, line, and time period; identify the data source, analytical assumptions, and whether a figure is **observed, corrected, estimated, or forecast**; and obtain a reproducible **evidence document or dataset**.

## P0 · Safety and accuracy (before expanding Public Beta)

- [x] Audit and rotate operational API keys/credentials where needed; mask sensitive values in logs. *(Nov 2026: database password rotated after an exposure; unused API key removed from the API service; API keys redacted from loader errors; secret scanning in pre-commit and CI.)*
- [x] Verify least-privilege access for Cloud Run, Cloud SQL, and job service accounts; review Secret Manager coverage. *(A dedicated service account per service, job and workflow; each secret readable only by its user.)*
- [x] Identify missing Cloud SQL triggers and verify any data impact. *(Four terrain guard triggers are missing; serving only reads those tables. Whether to add them is still open.)*
- [ ] Automate source-to-aggregate tests for missing/duplicate rows, preserved totals, and hourly allocations; prevent publication when critical checks fail. *(Checks run in the weekly workflow and a failure stops the BigQuery publish. The serving tables in Cloud SQL are rewritten before the checks run, so the API is not yet protected.)*
- [ ] Review source-data licensing, redistribution permissions, and attribution requirements.
- [ ] Check official OSM tile policy, required attribution, HTTP caching, and fallback options if usage grows.

**Definition of done:** New aggregates that fail critical integrity checks are not published, and known high-priority issues involving credentials and access rights are resolved.

## P1 · Reliable analytical data mart

- [ ] Define value types (observed/corrected/estimated/forecast) and dataset release identifiers.
- [x] Replace BigQuery's **outdated six-month load** with a validated rebuild covering all 32 months. *(34 months, refreshed by the weekly pipeline.)*
- [ ] Reconcile PostgreSQL and BigQuery metric definitions, totals, periods, and administrative-boundary versions. *(Monthly boarding totals are reconciled by a dbt test; definitions and boundary versions are not.)*
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
- [x] Choose a cloud deployment approach for Airflow and evaluate any always-on infrastructure costs. *(Decided against: Cloud Workflows runs the cloud pipeline; Cloud Composer starts around thirty times the database's monthly cost. Airflow stays for local development.)*
- [x] Assign ownership of each schedule to either Airflow or Cloud Scheduler and **prevent duplicate runs**. *(One weekly Cloud Scheduler trigger; the earlier monthly collector trigger is paused.)*
- [x] Decide whether Cloud Run Jobs should remain the execution layer for batch computations, separate from DAG orchestration. *(Yes: nine jobs, ordered by the workflow.)*
- [ ] Test idempotent loads, retries, failure notifications, checkpoints, and historical reprocessing. *(In place: idempotent upserts, catch-up of missed months, per-batch commits with retry and resume, forced reprocessing, alerts on failed runs and failed triggers. Still to show: one unattended run that loads a new month.)*
- [ ] Continue using existing GCP budget alerts and review actual monthly spend by service. *(Monthly budget with alerts at 50/90/100%; spend review after the first full month with batch rebuilds.)*
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

1. ~~Airflow deployment topology and division of responsibilities with Cloud Scheduler.~~ Decided November 2026: Cloud Scheduler → Workflows → Cloud Run Jobs in the cloud, Airflow local only.
2. ~~Batching, validation, and failure recovery strategy for the full 32-month BigQuery reload.~~ Decided November 2026: each rebuild exports from Cloud SQL, replaces staging, and dbt tests gate the marts.
3. Metrics, user interface, and report scope for the first complete external-use scenario.
4. Production Caffeine/Cloud Run latency metrics and operational cost targets.
