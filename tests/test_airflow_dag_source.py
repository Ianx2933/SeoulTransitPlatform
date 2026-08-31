"""Cheap source-level guard for the Airflow DAG shipped in the repository."""

from __future__ import annotations


def test_od_correction_dag_source_compiles(project_root):
    dag_path = project_root / "pipelines" / "airflow" / "dags" / "od_correction_dag.py"
    source = dag_path.read_text(encoding="utf-8")

    compile(source, str(dag_path), "exec")
