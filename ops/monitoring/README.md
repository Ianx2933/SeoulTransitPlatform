# Pipeline alerts

Two Cloud Monitoring alert policies send an email when the weekly pipeline does not run
cleanly. Without them a failed run would only be noticed by checking by hand.

| Policy | Fires when |
|---|---|
| `pipeline_failed_policy.json` | A `transit-pipeline` execution finishes `FAILED` or `CANCELLED` (metric `workflows.googleapis.com/finished_execution_count`) |
| `scheduler_failed_policy.json` | Cloud Scheduler logs an error starting `transit-pipeline-weekly`, so no execution exists for the first policy to see |

A long-running or stuck rebuild is not covered here; the budget alert catches a database
left on the batch tier.

## Create

```powershell
$ch = gcloud beta monitoring channels create --project seoul-transit-prod --display-name="Pipeline alerts" --type=email --channel-labels=email_address=<address> --format="value(name)"
gcloud alpha monitoring policies create --project seoul-transit-prod --policy-from-file=ops/monitoring/pipeline_failed_policy.json --notification-channels=$ch
gcloud alpha monitoring policies create --project seoul-transit-prod --policy-from-file=ops/monitoring/scheduler_failed_policy.json --notification-channels=$ch
```

## Test

A non-boolean `force` makes the workflow fail at the `decide` step, after the collector
and before the database is scaled up, so the test changes nothing. This holds only while no
new month is waiting to be loaded; with one, `decide` short-circuits and the full rebuild runs.

```powershell
gcloud workflows run transit-pipeline --location asia-northeast3 --project seoul-transit-prod --data '{\"force\":\"alert-test\"}'
```

The email arrives a few minutes after the run fails.
