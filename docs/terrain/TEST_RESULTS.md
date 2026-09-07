# Verification results

## Executed

- Python unit tests: 11 passed (original package).
- After the readability rewrite of `publish_terrain.py`, every assertion in
  `tests/test_publish_terrain.py` was re-run manually (pytest was unavailable
  offline) and all 21 checks passed, including the new `reference_slope`
  validation. Behaviour is unchanged from the reviewed version.
- Java 21 domain/DTO compilation and smoke checks: 3 passed (original package).

## NOT executed

- Full Maven build, Spring MVC/cache tests, PostGIS integration tests.
  Maven, Docker and PostgreSQL were unavailable; Maven Central DNS also failed.
- The SQL migrations have never run against a live database. This includes
  `01_terrain_schema.sql` (two plpgsql trigger functions), `04_activate_dataset.sql`
  and `05_retire_legacy_columns.sql`.
- `publish_terrain.py` has never connected to PostgreSQL. The staging tables,
  advisory lock, REPEATABLE READ isolation and the whole assignment query are
  untested against a real server.
- The Lombok conversion has not been compiled.

## What this means

The package is a reviewed implementation, not a validated one. The parts most
likely to fail on first contact are exactly the parts that have never run: the
trigger functions and the publication transaction.

Run `--dry-run` first. It rolls back, so it exercises staging, boundary
verification and dong assignment without writing anything.
