# Airflow image with the warehouse toolchain added.
#
# dbt, the BigQuery client, pyarrow and psycopg2 go into a separate virtualenv
# rather than Airflow's own environment. Airflow pins its dependencies through
# a constraints file, and installing dbt next to it can move a shared package
# to a version Airflow was not tested with. Tasks call the venv's binaries by
# full path, so the two never mix.
FROM apache/airflow:2.9.0

USER root
# Export folders live on a named volume mounted here. A new named volume copies
# the ownership of the directory it is mounted over, so creating it as the
# airflow user makes the volume writable by the tasks.
RUN mkdir -p /opt/airflow/exports && chown -R airflow:root /opt/airflow/exports

USER airflow
RUN python -m venv /home/airflow/warehouse-venv \
 && /home/airflow/warehouse-venv/bin/pip install --no-cache-dir \
      "dbt-core>=1.12,<1.13" \
      "dbt-bigquery>=1.12,<1.13" \
      "google-cloud-bigquery>=3.25" \
      "pyarrow>=22" \
      "psycopg2-binary>=2.9.12" \
 && /home/airflow/warehouse-venv/bin/dbt --version
