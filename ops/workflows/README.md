# Weekly pipeline (Cloud Workflows)

`transit_pipeline.yaml` is the cloud pipeline. Cloud Scheduler starts it every Tuesday
at 05:17 KST. `deploy.ps1` deploys the workflow and creates or updates the trigger.

```text
Cloud Scheduler (weekly)
  └─> Workflows: transit-pipeline
        collect ── transit-collector: loads every published month after the latest one in Cloud SQL
        │           and writes what it loaded to gs://seoul-transit-prod-pipeline/state/<execution>.json
        ├─ nothing new ──> end (about 20 s)
        └─ new month(s)
             scale Cloud SQL db-f1-micro -> db-custom-2-7680
             transit-month-day-count
             ── with PIPELINE_TABLE_TARGET=next, each writes <table>__next ──
             transit-dow-ratio          day-type x hour ratios by route and stop, least squares over all months
             transit-stop-pattern       the same at stop level
             transit-estimate-od        daily OD allocated to hours
             transit-stop-demand
             transit-integrated-light   bus + subway
             transit-validate           validate_rebuild.py on the __next copies; a failed check stops the run
             ───────────────────────────────────────────────────────────────
             transit-publish            publish_rebuild.py: swaps all five copies in, in one transaction
             transit-warehouse          Cloud SQL export -> BigQuery staging -> dbt build --target prod
             scale Cloud SQL back to db-f1-micro (also when any step above fails)
```

Until `transit-publish` runs, the API and the warehouse read the previous tables. A
run that fails at any step before it leaves them as they were; the `__next` copies
stay behind for inspection and are replaced by the next run. Writing the copies from
empty also keeps the tables compact. Updating them in place left each rebuilt table
up to twice its size, which slowed every later step that reads them whole.

The steps run one at a time. On a two-vCPU instance, running two write-heavy jobs at once
made both several times slower than running them in sequence.

## Running it

```powershell
# Status of the latest run
gcloud workflows executions list transit-pipeline --location asia-northeast3 --project seoul-transit-prod --limit 1

# Rebuild even when no new month was loaded (after a code change, for example)
gcloud workflows run transit-pipeline --location asia-northeast3 --project seoul-transit-prod --data '{\"force\":true}'

# Which job is running, and how long each took
gcloud run jobs executions list --region asia-northeast3 --project seoul-transit-prod --limit 10 --sort-by="~metadata.creationTimestamp"
```

## Runs

### 2026-11-08: solvers read the table in a few sorted passes

A forced rebuild after the ratio solvers stopped querying the hourly table once per
batch of 25 routes: 3 h 42 min instead of 9 h 30 min, same SHA-256 over the hourly OD.
Ratio 11 min 36 s (was 3 h 15 min), stop pattern 13 min 54 s (was 3 h 44 min). The
steps after them were slower than the day before (validation 40 min against 17), with
the rebuilt tables at about twice their row size; that run still updated tables in place.

### 2026-11-07: first runs

| Run | Result |
|---|---|
| No new month | Ended after the collector, 24 s |
| Forced full rebuild | SUCCEEDED in 9 h 30 min; the database went back to `db-f1-micro`; validation passed and the SHA-256 fingerprint over 12,347,976 rows matched the manual run |
| Started by Cloud Scheduler | SUCCEEDED, 18 s (no new month) |

Time of the forced rebuild by step: scale-up 6 min, month-day count 9 s, ratio 3 h 15 min,
stop pattern 3 h 44 min, OD allocation 39 min, stop demand 19 min, integration 23 min,
validation 17 min, warehouse about 43 min. The two ratio steps take most of the run: each
reads the hourly table once per batch of routes.

## Prerequisites

- The Cloud Run Jobs named above, on the pipeline image, with Cloud SQL attached and
  `PIPELINE_DB_URL` from Secret Manager. `transit-publish` runs
  `python -m pipelines.hourly_od_estimation.publish_rebuild`.
- Bucket `seoul-transit-prod-pipeline`. The collector's service account writes the state
  file; the workflow's reads it.
- Service account `transit-workflow-sa`, which needs to run the jobs with environment
  overrides, change the Cloud SQL tier, read the state bucket, write logs, and invoke the
  workflow (Cloud Scheduler calls it as this account). To list what it currently holds:

```powershell
gcloud projects get-iam-policy seoul-transit-prod --flatten="bindings[].members" --filter="bindings.members:transit-workflow-sa" --format="value(bindings.role)"
```

New IAM bindings can take several minutes to apply. The first run after adding one failed
on a permission that had not yet propagated and succeeded when retried later.
