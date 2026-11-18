"""Cheap source-level guard for the Airflow DAGs shipped in the repository."""

from __future__ import annotations


def test_every_dag_source_compiles(project_root):
    dags = sorted((project_root / "pipelines" / "airflow" / "dags").glob("*.py"))
    assert dags, "no DAG files found"
    for dag_path in dags:
        compile(dag_path.read_text(encoding="utf-8"), str(dag_path), "exec")
