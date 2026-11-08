"""
Swap the rebuilt __next tables in for the tables the API and the warehouse read.

The weekly workflow writes every rebuilt table to <table>__next, validates those
copies, and runs this only after validation passed. All five tables are swapped
in one transaction, so readers see either the previous set or the new one,
never a mix:

    ALTER TABLE t RENAME TO t__old; ALTER TABLE t__next RENAME TO t; DROP TABLE t__old

and the indexes of the new table get back the names they had, so names stay the
same from one rebuild to the next. Dropping the old tables returns their disk at
once; updating in place left a table up to twice its size after a rebuild
(2026-11-08: estimated_hourly_stop_demand 1,985 MB in Cloud SQL, 990 MB locally).

Renaming is safe here because nothing else refers to these tables by object:
no views, foreign keys, triggers, sequences or grants to other roles (checked in
Cloud SQL on 2026-11-08). The script checks views and foreign keys again before
changing anything.

    python -m pipelines.hourly_od_estimation.publish_rebuild
"""

from __future__ import annotations

import argparse
import os
import re

from sqlalchemy import text

from .common import NEXT_SUFFIX, make_engine

REBUILT_TABLES = [
    "dow_hourly_ratio",
    "dow_hourly_stop_pattern",
    "estimated_hourly_od",
    "estimated_hourly_stop_demand",
    "integrated_hourly_transit_demand_light",
]
OLD_SUFFIX = "__old"
INDEX_DEF = re.compile(r"^CREATE (UNIQUE )?INDEX (\S+) ON (\S+) (.+)$")


def quote(name: str) -> str:
    return '"' + name.replace('"', '""') + '"'


def index_shape(definition: str) -> tuple[bool, str]:
    """What an index is, without its name or its table: uniqueness, method, columns, predicate."""
    match = INDEX_DEF.match(definition.strip())
    if not match:
        raise ValueError(f"unrecognised index definition: {definition}")
    return bool(match.group(1)), match.group(4)


def match_indexes(live: dict[str, str], rebuilt: dict[str, str]) -> list[tuple[str, str]]:
    """
    Pair each index of the rebuilt table with the live index it copies.

    Both arguments map index name to definition. Returns (rebuilt name, live name)
    pairs. Fails unless every index has exactly one counterpart, because a swap
    that silently lost an index would leave the API scanning whole tables.
    """
    by_shape: dict[tuple[bool, str], list[str]] = {}
    for name, definition in live.items():
        by_shape.setdefault(index_shape(definition), []).append(name)
    pairs = []
    for name, definition in sorted(rebuilt.items()):
        candidates = by_shape.get(index_shape(definition))
        if not candidates:
            raise ValueError(f"rebuilt index {name} matches no live index: {definition}")
        pairs.append((name, candidates.pop(0)))
    left = [name for names in by_shape.values() for name in names]
    if left:
        raise ValueError(f"live indexes missing from the rebuilt table: {', '.join(sorted(left))}")
    return pairs


def plan_swap(table: str, live: dict[str, str], rebuilt: dict[str, str]) -> list[str]:
    """Statements that put <table>__next in place of <table>, keeping index names."""
    pairs = match_indexes(live, rebuilt)
    statements = [
        f"ALTER TABLE {quote(table)} RENAME TO {quote(table + OLD_SUFFIX)}",
        f"ALTER TABLE {quote(table + NEXT_SUFFIX)} RENAME TO {quote(table)}",
        f"DROP TABLE {quote(table + OLD_SUFFIX)}",
    ]
    # The old indexes are dropped with their table, which frees their names.
    statements += [f"ALTER INDEX {quote(new)} RENAME TO {quote(old)}"
                   for new, old in pairs if new != old]
    return statements


def indexes(conn, table: str) -> dict[str, str]:
    rows = conn.execute(text(
        "SELECT indexname, indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = :t"
    ), {"t": table}).all()
    return {row.indexname: row.indexdef for row in rows}


def dependents(conn, tables: list[str]) -> list[str]:
    """Views and foreign keys that would follow a renamed table instead of the new one."""
    rows = conn.execute(text("""
        SELECT DISTINCT v.relname || ' (view)' AS name
        FROM pg_depend d
        JOIN pg_rewrite r ON d.objid = r.oid
        JOIN pg_class v ON r.ev_class = v.oid
        JOIN pg_class t ON d.refobjid = t.oid
        WHERE t.relname = ANY(:tables) AND v.oid <> t.oid
        UNION
        SELECT conname || ' (foreign key)'
        FROM pg_constraint
        WHERE contype = 'f'
          AND (conrelid::regclass::text = ANY(:tables) OR confrelid::regclass::text = ANY(:tables))
    """), {"tables": tables}).all()
    return [row.name for row in rows]


def publish(engine, tables: list[str] = REBUILT_TABLES, lock_timeout: str = "60s") -> None:
    with engine.begin() as conn:
        conn.execute(text("SELECT set_config('lock_timeout', :v, true)"), {"v": lock_timeout})

        missing = [t for t in tables
                   if conn.execute(text("SELECT to_regclass(:n)"), {"n": t + NEXT_SUFFIX}).scalar() is None]
        if missing:
            raise SystemExit(f"Nothing published: no rebuilt copy of {', '.join(missing)}.")
        blocking = dependents(conn, tables)
        if blocking:
            raise SystemExit("Nothing published: renaming would leave these pointing at the old "
                             f"tables: {', '.join(blocking)}.")

        plans = {t: plan_swap(t, indexes(conn, t), indexes(conn, t + NEXT_SUFFIX)) for t in tables}
        for table, statements in plans.items():
            rows = conn.execute(text(f"SELECT COUNT(*) FROM {quote(table + NEXT_SUFFIX)}")).scalar()
            for statement in statements:
                conn.exec_driver_sql(statement)
            print(f"published {table}: {rows:,} rows")
    print("All rebuilt tables published.")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument(
        "--db-url",
        default=os.environ.get("PIPELINE_DB_URL"),
        required="PIPELINE_DB_URL" not in os.environ,
        help="SQLAlchemy URL. Defaults to PIPELINE_DB_URL.",
    )
    parser.add_argument("--lock-timeout", default="60s",
                        help="How long to wait for readers before giving up; nothing changes then.")
    args = parser.parse_args()
    publish(make_engine(args.db_url), lock_timeout=args.lock_timeout)


if __name__ == "__main__":
    main()
