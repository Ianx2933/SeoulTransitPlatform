# Data fixes

One-off corrections to data already in the database. Each file has been applied
once, to the local database and to Cloud SQL; its header says when. They are kept
so the numbers in the main README can be traced to the query that
produced them, not to be run again. Every fix copies the affected rows to a dated backup
table first, and re-running a fix stops because that table already exists.

Files with `diag` in the name only read. The others change data inside one transaction.

| File | What it does |
|---|---|
| `01_fix_tdata_stdcode.sql` | Clears the Incheon code `164000626` written by the T-data lookup and refills from the stop master (Data quality, fifth defect) |
| `02_diag_stdcode_gap.sql` | Classifies codes that disagree with the stop master: another stop on the same route, a stop with the same name, or neither |
| `03_diag_stdcode_source.sql` | Sequence gap to the stop that owns the code, and which other dates hold the same wrong code at the same position |
| `04_fix_stdcode_by_ars.sql` | Rewrites every code that disagrees with the stop master for its ARS (Data quality, sixth defect) |

Running a file against Cloud SQL needs the proxy on port 5433:

```powershell
cloud-sql-proxy seoul-transit-prod:asia-northeast3:transit-db --port 5433 --quota-project seoul-transit-prod
```

The backup tables hold the rows exactly as they were before each fix.
`analysis_table_final` has no primary key, so restoring means matching rows on
all of their columns; check the match count before updating anything.
