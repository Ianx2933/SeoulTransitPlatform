# Load district OD (od_dong_typical) into Cloud SQL

One-off load, 2026-11. Weekly refresh is still to be wired into the warehouse
step next to od-typical. Run after the weekly workflow has finished, and only
when no other Cloud SQL admin operation (export, import, patch) is running.

PowerShell, from the repository root, one line at a time.

1. Check the day types and size in BigQuery

    bq query --project_id=project-9c77d932-f0d2-47bb-947 --use_legacy_sql=false "SELECT day_type, COUNT(*) AS n FROM dbt_dev.fct_od_dong_mode_typical GROUP BY 1 ORDER BY 1"

   The API expects weekday, saturday and sunday_holiday.

2. Build the export table (codes attached)

    Get-Content -Raw ops\od_dong\export_od_dong.sql | bq query --project_id=project-9c77d932-f0d2-47bb-947 --use_legacy_sql=false --replace --destination_table=project-9c77d932-f0d2-47bb-947:dbt_dev.od_dong_typical_export

   (Pass the SQL on standard input: PowerShell 5 breaks a multi-line SQL
   string given as an argument, and the table is silently not made.)

3. Check every Seoul district got a code (expect 426 origins, plus 'outside')

    bq query --project_id=project-9c77d932-f0d2-47bb-947 --use_legacy_sql=false "SELECT day_type, COUNT(*) AS n, COUNT(DISTINCT origin_cd) AS origins, ROUND(SUM(total_rides)) AS rides FROM dbt_dev.od_dong_typical_export GROUP BY 1 ORDER BY 1"

4. Extract to GCS as CSV (no header)

    bq extract --project_id=project-9c77d932-f0d2-47bb-947 --destination_format=CSV --print_header=false dbt_dev.od_dong_typical_export gs://seoul-transit-prod-pipeline/od-dong/od_dong_typical.csv

5. Create the table (Cloud SQL proxy on 5433, PGPASSWORD set; check length only)

    & $psql -h 127.0.0.1 -p 5433 -U postgres -d Seoul_Transit -f database\schema\07_od_dong_typical.sql

6. Import (Cloud SQL admin operation: do not rerun while it is in progress)

    gcloud sql import csv transit-db gs://seoul-transit-prod-pipeline/od-dong/od_dong_typical.csv --database=Seoul_Transit --table=od_dong_typical "--columns=day_type,origin_cd,origin_name,dest_cd,dest_name,subway_rides,bus_rides,total_rides" --project seoul-transit-prod

   Quote the --columns option: PowerShell turns an unquoted comma list into
   space-separated words. The Cloud SQL service account needs read access on
   the bucket (granted 2026-11-17):

    $sa = gcloud sql instances describe transit-db --project seoul-transit-prod --format "value(serviceAccountEmailAddress)"
    gcloud storage buckets add-iam-policy-binding gs://seoul-transit-prod-pipeline --member "serviceAccount:$sa" --role roles/storage.objectViewer

7. Check and analyse

    & $psql -h 127.0.0.1 -p 5433 -U postgres -d Seoul_Transit -c "SELECT day_type, count(*), round(sum(total_rides)) FROM od_dong_typical GROUP BY 1 ORDER BY 1;"
    & $psql -h 127.0.0.1 -p 5433 -U postgres -d Seoul_Transit -c "ANALYZE od_dong_typical;"

   Row counts and rides must match step 3.

To reload later: TRUNCATE od_dong_typical, then steps 2-7 (or load into a
__next table and rename, like the other rebuilt tables).
