# Deploys the weekly pipeline: the Workflows definition and the Cloud Scheduler job
# that starts it. Safe to run again: the workflow is redeployed and the scheduler job
# is updated in place if it already exists.
#
#   powershell -ExecutionPolicy Bypass -File ops\workflows\deploy.ps1
#
# The Cloud Run Jobs the workflow calls, their service account and the state bucket
# must already exist (see ops/workflows/README.md).

param(
    [string]$Project = "seoul-transit-prod",
    [string]$Region = "asia-northeast3",
    [string]$Workflow = "transit-pipeline",
    [string]$SchedulerJob = "transit-pipeline-weekly",
    # Tuesdays 05:17 KST: clear of the 03:00 Cloud SQL backup and the Sunday
    # 04:00 maintenance window. The rebuild takes about 9.5 hours when a new
    # month arrives, and about 20 seconds when none has.
    [string]$Schedule = "17 5 * * 2",
    [string]$TimeZone = "Asia/Seoul",
    # The monthly collector-only trigger this workflow replaces. Left running, it
    # would load the new month first, and the workflow would then see nothing new
    # and skip the rebuild.
    [string]$LegacySchedulerJob = "transit-collector-monthly"
)

$sa = "transit-workflow-sa@$Project.iam.gserviceaccount.com"
$source = Join-Path $PSScriptRoot "transit_pipeline.yaml"
$uri = "https://workflowexecutions.googleapis.com/v1/projects/$Project/locations/$Region/workflows/$Workflow/executions"

function Assert-Ok([string]$step) {
    if ($LASTEXITCODE -ne 0) { throw "$step failed (exit $LASTEXITCODE)" }
}

Write-Host "== deploy workflow $Workflow"
gcloud workflows deploy $Workflow --source $source --location $Region --project $Project --service-account $sa
Assert-Ok "workflows deploy"

Write-Host "== scheduler job $SchedulerJob"
gcloud scheduler jobs describe $SchedulerJob --location $Region --project $Project --format="value(name)" 2>$null | Out-Null
$verb = if ($LASTEXITCODE -eq 0) { "update" } else { "create" }
gcloud scheduler jobs $verb http $SchedulerJob --location $Region --project $Project `
    --schedule="$Schedule" --time-zone="$TimeZone" `
    --http-method=POST --uri="$uri" --message-body='{}' `
    --oauth-service-account-email="$sa"
Assert-Ok "scheduler jobs $verb"

Write-Host "== legacy job $LegacySchedulerJob"
$legacy = gcloud scheduler jobs describe $LegacySchedulerJob --location $Region --project $Project --format="value(state)" 2>$null
if ($LASTEXITCODE -ne 0) {
    Write-Host "not found, nothing to pause"
} elseif ($legacy -eq "PAUSED") {
    Write-Host "already paused"
} else {
    gcloud scheduler jobs pause $LegacySchedulerJob --location $Region --project $Project
    Assert-Ok "pause $LegacySchedulerJob"
}

Write-Host "== done. Test without waiting for the schedule:"
Write-Host "gcloud scheduler jobs run $SchedulerJob --location $Region --project $Project"
