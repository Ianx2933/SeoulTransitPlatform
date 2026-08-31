# Curated outputs

This directory is the local landing area for pipeline-generated, analysis-ready
outputs. Generated CSVs are intentionally excluded from Git because they can be
reproduced from the documented raw/reference inputs and pipeline code.

Automated tests use small synthetic fixtures and do not depend on local curated
files, so a fresh clone can run the test suite without private or bulk data.
