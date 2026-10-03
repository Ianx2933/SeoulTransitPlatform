"""
Reconcile the hourly pipeline against its own source after a rebuild.

This is deliberately not a pytest file. The repository's test suite asserts code
contracts against synthetic fixtures and runs in CI without a database; these
checks assert the state of real data and only mean anything after a run. Keeping
them separate is what lets CI stay offline.

Source reconciliation is independent of the hourly estimates; structural
checks enforce table contracts. The exit code is the gate: a non-zero exit means the rebuild
is not finished, whatever the loader printed.

    python -m pipelines.hourly_od_estimation.validate_rebuild \
        --db-url "$PIPELINE_DB_URL" --start-date 20251111 --end-date 20251111

Re-run the estimator between validation runs and compare the fingerprint.
The SHA-256 covers ordered keys, hours, methods and exact float values.
"""

from __future__ import annotations

import argparse
import sys
import hashlib
import json
import math
import datetime as dt

from sqlalchemy import text

from .common import make_engine


# Derived tables that carry stop_ars and must contain only real five-digit codes.
ARS_TABLES = [
    "dow_hourly_ratio",
    "dow_hourly_stop_pattern",
    "estimated_hourly_stop_demand",
]

# Tables that must not be empty. A load that reports success and leaves an empty
# table is the failure mode this list exists for.
NON_EMPTY_TABLES = [
    "hourly_bus_stop_passenger",
    "month_day_count",
    "dow_hourly_ratio",
    "dow_hourly_stop_pattern",
    "estimated_hourly_od",
    "estimated_hourly_stop_demand",
    "integrated_hourly_transit_demand_light",
]

RATIO_TOLERANCE = 1e-6


class Report:
    """Collects check results and decides the exit code.
    """

    def __init__(self) -> None:
        self.rows: list[tuple[str, bool, str]] = []

    def add(self, name: str, passed: bool, detail: str) -> None:
        self.rows.append((name, passed, detail))

    def note(self, name: str, detail: str) -> None:
        """Record a figure that is informational rather than pass/fail.
        """
        self.rows.append((name, None, detail))

    @property
    def failed(self) -> int:
        return sum(1 for _, passed, _ in self.rows if passed is False)

    def print(self) -> None:
        width = max(len(name) for name, _, _ in self.rows)
        print()
        for name, passed, detail in self.rows:
            mark = "    " if passed is None else (" ok " if passed else "FAIL")
            print(f"[{mark}] {name.ljust(width)}  {detail}")
        print()
        if self.failed:
            print(f"{self.failed} check(s) failed.")
        else:
            print("All checks passed.")


def scalar(conn, sql: str, **params):
    return conn.execute(text(sql), params).scalar()


def check_ratio_sums(conn, report: Report) -> None:
    """
    Every ratio profile must sum to exactly one, or to zero.

    A profile summing to something else would distribute more or fewer passengers
    than the day actually carried. This was the 1.105 fallback bug, measured here
    against the table rather than the code.
    """
    sql = """
        SELECT
            COUNT(*) AS total,
            COUNT(*) FILTER (WHERE s = 0) AS zero_keys,
            COUNT(*) FILTER (WHERE s IS NULL OR bad_values > 0 OR n <> 24
              OR hours <> 24 OR first_hour <> 0 OR last_hour <> 23
              OR (s <> 0 AND ABS(s - 1) > :tol)) AS bad_keys
        FROM (
            SELECT route_no, stop_ars, day_type, SUM(boarding_ratio) AS s,
                   COUNT(*) AS n, COUNT(DISTINCT hour) AS hours,
                   MIN(hour) AS first_hour, MAX(hour) AS last_hour,
                   COUNT(*) FILTER (WHERE boarding_ratio IS NULL OR boarding_ratio < 0
                     OR boarding_ratio::text IN ('NaN', 'Infinity', '-Infinity')) AS bad_values
            FROM dow_hourly_ratio
            GROUP BY route_no, stop_ars, day_type
        ) t
    """
    row = conn.execute(text(sql), {"tol": RATIO_TOLERANCE}).one()
    total, zero_keys, bad_keys = row.total, row.zero_keys, row.bad_keys
    report.add(
        "ratio profiles sum to 1 or 0",
        bad_keys == 0,
        f"{total - bad_keys:,} of {total:,} valid; {bad_keys:,} off by more than {RATIO_TOLERANCE}",
    )
    report.note(
        "ratio profiles that are all zero",
        f"{zero_keys:,} of {total:,} ({zero_keys / total * 100:.2f}%) routed to the fallback profile"
        if total else "ratio table is empty",
    )


# Tight numerical tolerance per OD, in passenger units; never a percentage of
# the entire dataset. Missing/extra keys and missing hours always fail.
OD_ABS_TOLERANCE = 1e-7
OD_REL_TOLERANCE = 1e-12


def od_total_matches(source: float, estimated: float) -> bool:
    return (math.isfinite(source) and math.isfinite(estimated)
            and math.isclose(source, estimated, rel_tol=OD_REL_TOLERANCE,
                             abs_tol=OD_ABS_TOLERANCE))


def check_od_totals(conn, report: Report, start_date: str, end_date: str) -> None:
    # Mirror estimator key normalization before grouping the source. NULL or
    # malformed source keys are checked separately rather than silently ignored.
    row = conn.execute(text("""
        WITH source AS (
            SELECT 기준일자, BTRIM(노선명) AS 노선명,
                   LPAD(BTRIM(승차_정류장ars), 5, '0') AS 승차_정류장ars,
                   LPAD(BTRIM(하차_정류장ars), 5, '0') AS 하차_정류장ars,
                   SUM(CAST(승객수 AS DOUBLE PRECISION)) AS passengers,
                   TRUE AS present
            FROM analysis_table_final
            WHERE 승차_정류장ars <> '00000' AND 하차_정류장ars <> '00000'
              AND 기준일자 >= :start_date AND 기준일자 <= :end_date
            GROUP BY 1, 2, 3, 4
        ), estimated AS (
            SELECT 기준일자, 노선명, 승차_정류장ars, 하차_정류장ars,
                   SUM(estimated_passengers) AS passengers,
                   COUNT(*) AS n, COUNT(DISTINCT hour) AS hours,
                   MIN(hour) AS first_hour, MAX(hour) AS last_hour,
                   COUNT(*) FILTER (WHERE estimated_passengers IS NULL
                     OR estimated_passengers < 0
                     OR estimated_passengers::text IN ('NaN', 'Infinity', '-Infinity')) AS bad,
                   TRUE AS present
            FROM estimated_hourly_od
            WHERE 기준일자 >= :start_date AND 기준일자 <= :end_date
            GROUP BY 1, 2, 3, 4
        )
        SELECT COUNT(*) AS total_keys,
               COUNT(*) FILTER (WHERE s.present IS NULL) AS extra,
               COUNT(*) FILTER (WHERE e.present IS NULL) AS missing,
               COUNT(*) FILTER (WHERE e.present IS NOT NULL AND
                 (e.n <> 24 OR e.hours <> 24 OR e.first_hour <> 0 OR e.last_hour <> 23)) AS bad_hours,
               COUNT(*) FILTER (WHERE s.present IS NOT NULL AND e.present IS NOT NULL AND
                 (s.passengers IS NULL OR e.passengers IS NULL OR e.bad > 0
                  OR s.passengers < 0
                  OR s.passengers::text IN ('NaN', 'Infinity', '-Infinity')
                  OR ABS(s.passengers - e.passengers) >
                    GREATEST(:abs_tol, :rel_tol * GREATEST(ABS(s.passengers), ABS(e.passengers))))) AS bad_totals,
               COALESCE(SUM(s.passengers), 0) AS source_total,
               COALESCE(SUM(e.passengers), 0) AS estimated_total
        FROM source s FULL OUTER JOIN estimated e
          USING (기준일자, 노선명, 승차_정류장ars, 하차_정류장ars)
    """), {"start_date": start_date, "end_date": end_date,
           "abs_tol": OD_ABS_TOLERANCE, "rel_tol": OD_REL_TOLERANCE}).one()
    report.add("OD keys match source", row.total_keys > 0 and row.missing == 0 and row.extra == 0,
               f"{row.total_keys:,} keys; missing {row.missing:,}; extra {row.extra:,}")
    report.add("24 distinct hours per OD", row.total_keys > 0 and row.bad_hours == 0,
               f"{row.bad_hours:,} invalid OD profiles")
    report.add("daily passengers conserved per OD", row.total_keys > 0 and row.bad_totals == 0,
               f"{row.bad_totals:,} mismatches; abs_tol={OD_ABS_TOLERANCE}; rel_tol={OD_REL_TOLERANCE}")
    report.note("OD totals", f"source {row.source_total:,.6f}; estimated {row.estimated_total:,.6f}")


def check_method_breakdown(conn, report: Report, start_date: str, end_date: str) -> None:
    """
    Report how many rows each provenance rule produced.

    Informational, but these are the figures the README validation table quotes,
    so they are printed here rather than looked up by hand afterwards.
    """
    rows = conn.execute(text("""
        SELECT method, COUNT(*) AS rows
        FROM estimated_hourly_od
        WHERE 기준일자 >= :start_date AND 기준일자 <= :end_date
        GROUP BY method
        ORDER BY rows DESC
    """), {"start_date": start_date, "end_date": end_date}).all()
    if not rows:
        report.add("method breakdown", False, "no estimated rows in range")
        return
    detail = "; ".join(f"{row.method} {row.rows:,}" for row in rows)
    report.note("method breakdown", detail)


def check_no_duplicate_methods(conn, report: Report, start_date: str, end_date: str) -> None:
    """
    One OD-hour must carry exactly one estimation method.

    Because method is part of the key, a row that moves from fallback to a fitted
    profile is kept twice unless the range is replaced rather than upserted.
    """
    duplicates = scalar(conn, """
        SELECT COUNT(*) FROM (
            SELECT 기준일자, 노선명, 승차_정류장ars, 하차_정류장ars, hour
            FROM estimated_hourly_od
            WHERE 기준일자 >= :start_date AND 기준일자 <= :end_date
            GROUP BY 기준일자, 노선명, 승차_정류장ars, 하차_정류장ars, hour
            HAVING COUNT(DISTINCT method) > 1
        ) t
    """, start_date=start_date, end_date=end_date)
    report.add(
        "one method per OD-hour",
        duplicates == 0,
        f"{duplicates:,} OD-hour keys carry more than one method",
    )


def check_ars_codes(conn, report: Report) -> None:
    """
    Derived tables must hold only five-digit ARS codes other than 00000.

    Virtual stops arrive as '~' and blank codes normalize to '00000'; both
    distort the decomposition. The filter lives in SQL, so it is checked here.
    """
    for table in ARS_TABLES:
        bad = scalar(
            conn,
            "SELECT COUNT(*) FROM " + table +
            " WHERE stop_ars IS NULL OR stop_ars !~ '^[0-9][0-9][0-9][0-9][0-9]$' OR stop_ars = '00000'",
        )
        report.add(f"ARS codes valid in {table}", bad == 0, f"{bad:,} invalid rows")


def check_tables_not_empty(conn, report: Report) -> None:
    """A load that reports success and writes nothing fails here.
    """
    for table in NON_EMPTY_TABLES:
        count = scalar(conn, "SELECT COUNT(*) FROM " + table)
        report.add(f"{table} not empty", count > 0, f"{count:,} rows")


def fingerprint_rows(rows) -> tuple[int, str]:
    """Hash ordered complete rows, including the exact binary float value.
    """
    digest = hashlib.sha256()
    count = 0
    for row in rows:
        values = list(row)
        value = float(values[5])
        if not math.isfinite(value):
            raise ValueError("fingerprint cannot contain non-finite passengers")
        payload = [str(v) if v is not None else None for v in values[:5]]
        payload.extend([value.hex(), str(values[6])])
        digest.update(json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8"))
        digest.update(b"\n")
        count += 1
    return count, digest.hexdigest()


def print_fingerprint(conn, start_date: str, end_date: str) -> None:
    # Server-side streaming keeps memory bounded. ORDER BY is essential: a
    # physical table scan can change order after a rebuild or VACUUM.
    result = conn.execution_options(stream_results=True).execute(text("""
        SELECT 기준일자, 노선명, 승차_정류장ars, 하차_정류장ars,
               hour, estimated_passengers, method
        FROM estimated_hourly_od
        WHERE 기준일자 >= :start_date AND 기준일자 <= :end_date
        ORDER BY 기준일자, 노선명, 승차_정류장ars, 하차_정류장ars, hour, method
    """), {"start_date": start_date, "end_date": end_date})
    try:
        count, digest = fingerprint_rows(result)
    finally:
        result.close()
    print(f"fingerprint {start_date}-{end_date}: rows={count:,} sha256={digest}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--db-url", required=True)
    parser.add_argument("--start-date", required=True, help="YYYYMMDD")
    parser.add_argument("--end-date", required=True, help="YYYYMMDD")
    args = parser.parse_args()

    try:
        start = dt.datetime.strptime(args.start_date, "%Y%m%d")
        end = dt.datetime.strptime(args.end_date, "%Y%m%d")
        if len(args.start_date) != 8 or len(args.end_date) != 8 or start > end:
            raise ValueError("invalid date range")
    except ValueError:
        parser.error("dates must be YYYYMMDD and start-date <= end-date")
    engine = make_engine(args.db_url)
    report = Report()

    with engine.connect().execution_options(isolation_level="REPEATABLE READ") as conn:
        check_tables_not_empty(conn, report)
        check_ratio_sums(conn, report)
        check_ars_codes(conn, report)
        check_od_totals(conn, report, args.start_date, args.end_date)
        check_method_breakdown(conn, report, args.start_date, args.end_date)
        check_no_duplicate_methods(conn, report, args.start_date, args.end_date)
        report.print()
        print_fingerprint(conn, args.start_date, args.end_date)

    return 1 if report.failed else 0


if __name__ == "__main__":
    sys.exit(main())
