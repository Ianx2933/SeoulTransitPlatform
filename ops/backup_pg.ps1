<#
Weekly backup of the local Seoul_Transit PostgreSQL database to GCS.

  1. pg_dump (custom format) to a local folder
  2. check the dump: readable table of contents, expected tables present, sane size
  3. upload to gs://seoul-transit-prod-pg-backups and compare sizes
  4. keep only the newest local copy

Background: in October 2026 the local data directory disappeared and the
database was restored from a single dump that happened to exist. This script
makes sure there is always a recent copy off this machine.

Password: read by pg_dump from %APPDATA%\postgresql\pgpass.conf, never from
this file. Old objects are deleted by the bucket's 60-day lifecycle rule.
#>

$ErrorActionPreference = 'Continue'   # native tools write progress to stderr; exit codes are checked instead

$PgBin    = 'C:\Program Files\PostgreSQL\18\bin'
$Gcloud   = Join-Path $env:LOCALAPPDATA 'Google\Cloud SDK\google-cloud-sdk\bin\gcloud.cmd'
$Database = 'Seoul_Transit'
$Bucket   = 'gs://seoul-transit-prod-pg-backups'
$Project  = 'seoul-transit-prod'
$LocalDir = Join-Path $env:LOCALAPPDATA 'pg_backups'
$Log      = Join-Path $LocalDir 'backup.log'

# Tables the platform cannot run without. A dump missing any of them fails.
$RequiredTables = @(
  'hourly_bus_stop_passenger',
  'estimated_hourly_od',
  'integrated_hourly_transit_demand_light',
  'bus_stop_location',
  'month_day_count'
)
$MinBytes = 300MB   # the October 2026 dump was 0.81 GB

function Write-Log($msg) {
  $line = '{0}  {1}' -f (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'), $msg
  Write-Output $line
  Add-Content -Path $Log -Value $line -Encoding utf8
}

New-Item -ItemType Directory -Force $LocalDir | Out-Null
$name = 'seoul_transit_{0}.dump' -f (Get-Date -Format 'yyyyMMdd_HHmm')
$file = Join-Path $LocalDir $name

try {
  Write-Log "START $name"

  # 1. dump
  & "$PgBin\pg_dump.exe" -h localhost -p 5432 -U postgres -w -Fc -Z 6 -f $file $Database
  if ($LASTEXITCODE -ne 0) { throw "pg_dump exited with $LASTEXITCODE (is the service running? is pgpass.conf set?)" }

  # 2. check
  $size = (Get-Item $file).Length
  if ($size -lt $MinBytes) { throw ('dump is only {0:N0} MB' -f ($size / 1MB)) }

  $toc = & "$PgBin\pg_restore.exe" -l $file
  if ($LASTEXITCODE -ne 0) { throw "pg_restore could not read the dump ($LASTEXITCODE)" }
  $dataEntries = @($toc | Where-Object { $_ -like '* TABLE DATA *' } | ForEach-Object { [string]$_ })
  $missing = @($RequiredTables | Where-Object { -not ($dataEntries -match " $_ ") })
  if ($missing.Count -gt 0) { throw ('missing table data: ' + ($missing -join ', ')) }
  Write-Log ('dump ok: {0:N0} MB, {1} tables with data' -f ($size / 1MB), $dataEntries.Count)

  # 3. upload and compare size
  & $Gcloud storage cp $file "$Bucket/$name" --project=$Project --quiet
  if ($LASTEXITCODE -ne 0) { throw "upload failed ($LASTEXITCODE)" }
  $remote = & $Gcloud storage ls -l "$Bucket/$name" --project=$Project
  if ($LASTEXITCODE -ne 0) { throw "could not list uploaded object ($LASTEXITCODE)" }
  $remoteBytes = [int64](($remote | Select-Object -First 1).Trim() -split '\s+')[0]
  if ($remoteBytes -ne $size) { throw "size mismatch: local $size, remote $remoteBytes" }
  Write-Log "uploaded $Bucket/$name"

  # 4. keep only the newest local dump
  Get-ChildItem $LocalDir -Filter 'seoul_transit_*.dump' |
    Sort-Object LastWriteTime -Descending | Select-Object -Skip 1 |
    ForEach-Object { Remove-Item $_.FullName; Write-Log "removed old local copy $($_.Name)" }

  Write-Log 'DONE'
  exit 0
}
catch {
  Write-Log "FAILED: $_"
  exit 1
}
