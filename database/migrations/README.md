# Migrations

Changes to a database that already exists (local and Cloud SQL). A new database
does not need them: `database/schema/run_all.sql` already creates the result.
Each file is safe to run twice. Run with
`psql ... -v ON_ERROR_STOP=1 -f database/migrations/<file>`.

| File | What it does | Applied to |
|---|---|---|
| `01_rename_analysis_table_final.sql` | `analysis_table_final` → `analysis_table_final_legacy`, with its indexes and key | local, Cloud SQL |
| `02_bus_route_path_variants.sql` | Adds `bus_route_path_route.od_route_nm` (one TOPIS route stored as several API routes) | local, Cloud SQL |
| `03_bus_route_path_history.sql` | Adds `path_since`, `path_sig` and the `bus_route_path_version*` tables (earlier paths of changed routes) | |
