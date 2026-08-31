# Reference data

Reference datasets used to resolve route and stop identifiers live here during
local pipeline runs but are intentionally not committed as bulk CSV assets.

The OD correction pipeline expects a validated reference containing route IDs,
stop sequence, ARS identifiers, standard stop codes, stop names, and passenger
counts. See `pipelines/od_correction/src/README.md` for the exact contract and
`docs/deployment/database_setup.md` for data-loading order.

Large/reference CSV files are ignored by default. Small deterministic fixtures
used by automated tests belong under `tests/`, not here.
