# Stop master for OD correction

`od_correction_pipeline.py` takes standard codes from a stop master first and
fails if any output code disagrees with it. Without `--stop-master` it falls
back to the reference file alone and prints a warning.

The master is one pipe-separated file with two columns, `정류장번호` (ARS) and
`노드id`. It combines the Seoul stop table with the stops outside Seoul that
only `integrated_bus_stop_location` holds, so routes that cross the city
boundary are checked too. ARS numbers with more than one node, and the
placeholder `00000`, are left out when the pipeline loads the file.

Export it from the local database (one line):

```powershell
& "C:\Program Files\PostgreSQL\18\bin\psql.exe" -U postgres -d Seoul_Transit -c "\copy (SELECT 정류장번호, 노드id FROM bus_stop_location UNION SELECT ars_id, canonical_node_id FROM integrated_bus_stop_location WHERE ars_id IS NOT NULL AND ars_id NOT IN (SELECT 정류장번호 FROM bus_stop_location)) TO 'C:/Users/miyum/stop_master.csv' WITH (FORMAT csv, DELIMITER '|', HEADER, ENCODING 'UTF8')"
```

Then pass it to the pipeline:

```powershell
python pipelines/od_correction/src/od_correction_pipeline.py `
  --raw <raw OD csv> --reference <reference csv> --output <curated csv> `
  --stop-master C:/Users/miyum/stop_master.csv
```

The run ends with a stop master check. Any row whose code disagrees with the
master for its ARS fails the run before the output is written.
