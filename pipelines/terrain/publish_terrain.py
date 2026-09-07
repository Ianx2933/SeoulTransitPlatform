"""Atomically publish a versioned terrain snapshot without changing the stop master.

A snapshot is written in one transaction and only becomes visible once
terrain_active_dataset points at it. Published snapshots are immutable, which
is what allows the API to cache aggregates keyed by dataset_id alone.
"""

import argparse
import csv
import json
import math
import os
from datetime import datetime, timezone
from pathlib import Path

# Canonical field names the publisher works in. CSV headers are mapped onto
# these via --csv-map so a differently-named export can still be published.

COLUMNS = ('node_id', 'stop_no', 'stop_name', 'longitude', 'latitude', 'elev_m', 'slope_pct')

# Advisory lock key. Serialises concurrent publications against each other and
# against activation, without locking any user table.

PUBLISH_LOCK = (718403, 20260907)

# Batch size for staging inserts. Large enough to amortise round trips, small
# enough that a malformed source file fails before building a huge list.

BATCH_SIZE = 1000


class PublishError(ValueError):
    """Publication was rejected."""


def number(value, name, optional=False, nodata=-9999.0):
    """Parse a numeric field, treating the raster NoData value as missing."""
    if value is None or str(value).strip() == '':
        if optional:
            return None
        raise PublishError(f'{name} is required')

    try:
        value = float(value)
    except (ValueError, TypeError, OverflowError) as exc:
        raise PublishError(f'{name} must be numeric') from exc

    # NaN and infinity would pass the range checks below and land in the database as unusable values.

    if not math.isfinite(value):
        raise PublishError(f'{name} must be finite')

    if optional and nodata is not None and value == nodata:
        return None
    return value


def normalize_row(row, nodata=-9999.0):
    """Validate one source row and return it as a positional tuple."""
    node_id = str(row.get('node_id') or '')
    # Reject padded IDs rather than trimming them: silently changing an
    # identifier would break the join back to the demand tables.

    if not node_id.strip() or node_id != node_id.strip():
        raise PublishError('node_id must be a nonblank, exact source identifier')

    name = str(row.get('stop_name') or '')
    if not name.strip():
        raise PublishError('stop_name is required')

    stop_no = row.get('stop_no')
    stop_no = None if stop_no is None or str(stop_no) == '' else str(stop_no)

    longitude = number(row.get('longitude'), 'longitude')
    latitude = number(row.get('latitude'), 'latitude')
    if not (-180 <= longitude <= 180 and -90 <= latitude <= 90):
        raise PublishError('coordinates must be EPSG:4326 longitude/latitude')

    elev_m = number(row.get('elev_m'), 'elev_m', True, nodata)
    slope_pct = number(row.get('slope_pct'), 'slope_pct', True, nodata)
    # Percentage grade is a magnitude, so it has no upper bound in principle,
    # but a negative value means the source is not a grade at all.

    if slope_pct is not None and slope_pct < 0:
        raise PublishError('slope_pct must be non-negative')

    return node_id, stop_no, name, longitude, latitude, elev_m, slope_pct


def csv_rows(path, mapping=None):
    """Stream canonical rows from a CSV file."""
    mapping = mapping or {}
    if set(mapping) - set(COLUMNS):
        raise PublishError('Unknown canonical column in mapping')

    # utf-8-sig strips the byte order mark Excel writes.

    with open(path, encoding='utf-8-sig', newline='') as handle:
        reader = csv.DictReader(handle)
        missing = [c for c in COLUMNS if mapping.get(c, c) not in (reader.fieldnames or ())]
        if missing:
            raise PublishError('Missing CSV columns: ' + ', '.join(missing))

        for line_no, row in enumerate(reader, 2):
            # DictReader puts overflowing fields under the None key, which
            # means the row has more columns than the header.

            if None in row:
                raise PublishError(f'CSV line {line_no}: extra fields')
            yield {c: row[mapping.get(c, c)] for c in COLUMNS}

def master_rows(conn):
    """Stream rows from the legacy master columns, for the initial migration.

    This path exists only to move the already-computed values into the first
    snapshot. Once that snapshot is published, prefer --csv straight from the
    raster pipeline and retire the master columns (see 05_retire_legacy_columns.sql).
    """
    with conn.cursor() as cur:
        cur.execute('''
            SELECT "노드id"::text, "정류장번호"::text, "정류장명"::text,
                   ST_X(geom), ST_Y(geom), elev_m, slope_pct
            FROM public.bus_stop_location
            ORDER BY "노드id"::text COLLATE "C"
        ''')
        while True:
            batch = cur.fetchmany(2000)
            if not batch:
                break
            for values in batch:
                yield dict(zip(COLUMNS, values))

def _validate_arguments(dataset_id, source_id, slope_method, boundary_date,
                        reference_slope, processed_at):
    """Check publication metadata before touching the database."""
    if not dataset_id or len(dataset_id) > 160 or not dataset_id.strip():
        raise PublishError('dataset_id must be 1-160 nonblank characters')
    if not source_id.strip() or not slope_method.strip():
        raise PublishError('source_id and slope_method are required')

    try:
        datetime.strptime(boundary_date, '%Y%m%d')
    except ValueError as exc:
        raise PublishError('boundary_date must be a valid YYYYMMDD date') from exc

    if not math.isfinite(reference_slope) or reference_slope < 0:
        raise PublishError('reference_slope must be a finite, non-negative grade')

    processed_at = processed_at or datetime.now(timezone.utc)
    if processed_at.tzinfo is None:
        # A naive timestamp would be stored as if it were UTC, silently
        # misdating the provenance record by the local offset.
        raise PublishError('processed_at must include a timezone')
    return processed_at

def _stage_source_rows(cur, rows, nodata, execute_values):
    """Load and validate every source row into a temporary table."""
    cur.execute('''
        CREATE TEMP TABLE terrain_stage(
            node_id text PRIMARY KEY,
            stop_no text,
            stop_name text NOT NULL,
            geom geometry(Point,4326) NOT NULL,
            elev_m float8,
            slope_pct float8
        ) ON COMMIT DROP
    ''')

    batch = []
    count = 0
    for count, row in enumerate(rows, 1):
        try:
            batch.append(normalize_row(row, nodata))
        except PublishError as exc:
            raise PublishError(f'Source row {count}: {exc}') from exc
        if len(batch) >= BATCH_SIZE:
            _insert_batch(cur, batch, execute_values)
            batch = []
    if batch:
        _insert_batch(cur, batch, execute_values)

    if not count:
        raise PublishError('Source contains no stops')
    return count

def _verify_boundary_snapshot(cur, boundary_date):
    """Reject boundary data that would make the assignment ambiguous."""
    cur.execute('''
        SELECT COUNT(*),
               COUNT(*) FILTER (WHERE adm_cd IS NULL OR btrim(adm_cd) = ''
                             OR adm_nm IS NULL OR btrim(adm_nm) = ''
                             OR geom IS NULL),
               COUNT(*) FILTER (WHERE geom IS NOT NULL
                             AND (ST_SRID(geom) <> 4326 OR NOT ST_IsValid(geom)))
        FROM public.admin_dong_boundary
        WHERE base_date = %s
    ''', (boundary_date,))
    rows, missing, invalid = cur.fetchone()
    if not rows or missing or invalid:
        raise PublishError(f'Boundary snapshot: rows={rows}, missing={missing}, invalid={invalid}')

    # One code carrying two names means the boundary export itself is
    # inconsistent, and the resulting dong labels could not be trusted.

    cur.execute('''
        SELECT adm_cd FROM public.admin_dong_boundary
        WHERE base_date = %s
        GROUP BY adm_cd HAVING COUNT(DISTINCT adm_nm) <> 1
        LIMIT 1
    ''', (boundary_date,))
    if cur.fetchone():
        raise PublishError('Conflicting names for one boundary code')

def _assign_dongs(cur, boundary_date, stop_count):
    """Resolve each stop to exactly one administrative dong.

    ST_Covers matches points on the shared edge of two polygons, so a stop can
    have several candidates. Interior matches win; ties break on adm_cd so the
    result is deterministic across runs.
    """
    # Dissolve multi-row dongs into one geometry per code first, so a dong
    # split across several rows is not counted as several candidates.

    cur.execute('''
        CREATE TEMP TABLE terrain_boundary_stage ON COMMIT DROP AS
        SELECT adm_cd::text AS adm_cd,
               MIN(adm_nm)::text AS adm_nm,
               ST_UnaryUnion(ST_Collect(geom)) AS geom
        FROM public.admin_dong_boundary
        WHERE base_date = %s
        GROUP BY adm_cd
    ''', (boundary_date,))
    cur.execute('CREATE UNIQUE INDEX ON terrain_boundary_stage(adm_cd)')
    cur.execute('CREATE INDEX ON terrain_boundary_stage USING GIST(geom)')
    cur.execute('ANALYZE terrain_stage')
    cur.execute('ANALYZE terrain_boundary_stage')

    cur.execute('''
        CREATE TEMP TABLE terrain_assignment_stage ON COMMIT DROP AS
        WITH candidates AS (
            SELECT s.node_id, d.adm_cd, d.adm_nm,
                   ST_Contains(d.geom, s.geom) AS interior
            FROM terrain_stage s
            JOIN terrain_boundary_stage d ON ST_Covers(d.geom, s.geom)
        ), ranked AS (
            SELECT *,
                   ROW_NUMBER() OVER (
                       PARTITION BY node_id
                       ORDER BY interior DESC, adm_cd COLLATE "C") AS rn,
                   COUNT(*) OVER (PARTITION BY node_id) AS candidate_count,
                   COUNT(*) FILTER (WHERE interior)
                       OVER (PARTITION BY node_id) AS interior_count
            FROM candidates
        )
        SELECT s.node_id, r.adm_cd, r.adm_nm,
               CASE WHEN r.adm_cd IS NULL THEN 'unmatched'
                    WHEN r.interior THEN 'interior'
                    ELSE 'boundary' END AS assignment_method,
               COALESCE(r.candidate_count, 0)::integer AS candidate_count,
               COALESCE(r.interior_count, 0)::integer AS interior_count
        FROM terrain_stage s
        LEFT JOIN ranked r ON r.node_id = s.node_id AND r.rn = 1
    ''')

    # A point strictly inside two polygons means the boundary layer overlaps,
    # which no tie-break can legitimately resolve.

    cur.execute('SELECT COUNT(*) FROM terrain_assignment_stage WHERE interior_count > 1')
    overlaps = cur.fetchone()[0]
    if overlaps:
        raise PublishError(f'{overlaps} stops lie inside multiple administrative polygons')

    cur.execute('CREATE UNIQUE INDEX ON terrain_assignment_stage(node_id)')
    cur.execute('''
        SELECT COUNT(*),
               COUNT(*) FILTER (WHERE adm_cd IS NOT NULL),
               COUNT(*) FILTER (WHERE candidate_count > 1)
        FROM terrain_assignment_stage
    ''')
    assignment_rows, assigned, ambiguous = cur.fetchone()
    if assignment_rows != stop_count:
        raise PublishError('Assignment cardinality mismatch')
    return assigned, ambiguous

def _write_snapshot(cur, dataset_id, source_id, slope_method, boundary_date,
                    processed_at, counts, activate):
    """Insert the snapshot and mark it published."""
    stop_count, measured, assigned, ambiguous = counts

    cur.execute('''
        INSERT INTO public.terrain_dataset
            (dataset_id, source_id, slope_method, boundary_base_date, processed_at)
        VALUES (%s, %s, %s, %s, %s)
    ''', (dataset_id, source_id, slope_method, boundary_date, processed_at))

    cur.execute('''
        INSERT INTO public.bus_stop_terrain_stats
            (dataset_id, node_id, stop_no, stop_name, geom, elev_m, slope_pct)
        SELECT %s, node_id, stop_no, stop_name, geom, elev_m, slope_pct
        FROM terrain_stage
    ''', (dataset_id,))

    cur.execute('''
        INSERT INTO public.terrain_stop_dong_assignment
            (dataset_id, node_id, adm_cd, adm_nm, assignment_method, candidate_count)
        SELECT %s, node_id, adm_cd, adm_nm, assignment_method, candidate_count
        FROM terrain_assignment_stage
    ''', (dataset_id,))

    # Setting published_at fires the guard trigger, which re-counts the child
    # rows and rejects the update if the stored counts disagree.

    cur.execute('''
        UPDATE public.terrain_dataset
        SET published_at = now(), stop_count = %s, measured_stop_count = %s,
            assigned_stop_count = %s, ambiguous_boundary_stop_count = %s
        WHERE dataset_id = %s
    ''', (stop_count, measured, assigned, ambiguous, dataset_id))

    if activate:
        cur.execute('''
            INSERT INTO public.terrain_active_dataset(singleton, dataset_id, activated_at)
            VALUES (TRUE, %s, now())
            ON CONFLICT (singleton) DO UPDATE
            SET dataset_id = EXCLUDED.dataset_id, activated_at = EXCLUDED.activated_at
        ''', (dataset_id,))

def publish(conn, *, dataset_id, source_id, slope_method, boundary_date, rows,
            activate=False, dry_run=False, nodata=-9999.0, expected_stops=None,
            expected_at_least=None, reference_slope=8.0, processed_at=None):
    """Publish one snapshot in a single transaction.

    expected_stops and expected_at_least are optional guards: when supplied,
    publication fails unless the source reproduces those counts. Use them to
    confirm a new run agrees with the figures already reported.
    """
    processed_at = _validate_arguments(dataset_id, source_id, slope_method,
                                       boundary_date, reference_slope, processed_at)

    from psycopg2.extras import execute_values
    from psycopg2.extensions import TRANSACTION_STATUS_IDLE

    # An open caller transaction would silently widen this one's scope and
    # defeat the all-or-nothing guarantee.

    if conn.get_transaction_status() != TRANSACTION_STATUS_IDLE:
        raise PublishError('Publisher requires an idle connection; '
                           'commit or roll back the caller transaction first')

    try:
        with conn:
            with conn.cursor() as cur:
                cur.execute('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
                cur.execute('SELECT pg_advisory_xact_lock(%s, %s)', PUBLISH_LOCK)

                cur.execute('SELECT 1 FROM public.terrain_dataset WHERE dataset_id = %s',
                            (dataset_id,))
                if cur.fetchone():
                    raise PublishError('Dataset ID already exists; choose a new version')

                stop_count = _stage_source_rows(cur, rows, nodata, execute_values)
                if expected_stops is not None and stop_count != expected_stops:
                    raise PublishError(f'Expected {expected_stops} stops, received {stop_count}')

                cur.execute('''
                    SELECT COUNT(slope_pct), COUNT(*) FILTER (WHERE slope_pct >= %s)
                    FROM terrain_stage
                ''', (reference_slope,))
                measured, at_least = cur.fetchone()
                if not measured:
                    raise PublishError('No valid slope measurements supplied')
                if expected_at_least is not None and at_least != expected_at_least:
                    raise PublishError(f'Expected {expected_at_least} stops at '
                                       f'>={reference_slope}%, received {at_least}')

                _verify_boundary_snapshot(cur, boundary_date)
                assigned, ambiguous = _assign_dongs(cur, boundary_date, stop_count)

                report = dict(
                    dataset_id=dataset_id,
                    stop_count=stop_count,
                    measured_stop_count=measured,
                    reference_slope_pct=reference_slope,
                    at_least_reference=at_least,
                    assigned_stop_count=assigned,
                    unmatched_stop_count=stop_count - assigned,
                    ambiguous_boundary_stop_count=ambiguous,
                    boundary_base_date=boundary_date,
                    activated=activate and not dry_run,
                    dry_run=dry_run,
                )

                if dry_run:
                    conn.rollback()
                    return report

                _write_snapshot(cur, dataset_id, source_id, slope_method, boundary_date,
                                processed_at, (stop_count, measured, assigned, ambiguous),
                                activate)
                return report
    except Exception:
        conn.rollback()
        raise


def _insert_batch(cur, batch, execute_values):
    execute_values(cur, '''
        INSERT INTO terrain_stage(node_id, stop_no, stop_name, geom, elev_m, slope_pct)
        VALUES %s
    ''', batch,
        template='(%s,%s,%s,ST_SetSRID(ST_MakePoint(%s,%s),4326),%s,%s)',
        page_size=BATCH_SIZE)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument('--db-url', default=os.environ.get('SEOUL_TRANSIT_DB'))

    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument('--csv', type=Path,
                        help='Canonical CSV produced by the raster pipeline')
    source.add_argument('--from-master', action='store_true',
                        help='Migrate values already stored on bus_stop_location')

    parser.add_argument('--csv-map', type=Path,
                        help='JSON mapping canonical fields to actual CSV headers')
    for name in ('dataset-id', 'source-id', 'slope-method', 'boundary-date'):
        parser.add_argument('--' + name, required=True)

    parser.add_argument('--processed-at')
    parser.add_argument('--nodata-value', type=float, default=-9999.0)
    parser.add_argument('--reference-slope', type=float, default=8.0,
                        help='Grade used by --expected-at-least (default 8%%)')
    parser.add_argument('--expected-stops', type=int)
    parser.add_argument('--expected-at-least', type=int)
    parser.add_argument('--activate', action='store_true')
    parser.add_argument('--dry-run', action='store_true')
    return parser


def main(argv=None):
    parser = build_parser()
    args = parser.parse_args(argv)

    if not args.db_url:
        parser.error('Set SEOUL_TRANSIT_DB or --db-url')
    if not math.isfinite(args.nodata_value):
        parser.error('nodata-value must be finite')
    for name in ('expected_stops', 'expected_at_least'):
        if getattr(args, name) is not None and getattr(args, name) < 0:
            parser.error(name + ' must be non-negative')

    processed_at = (datetime.fromisoformat(args.processed_at.replace('Z', '+00:00'))
                    if args.processed_at else None)
    mapping = json.loads(args.csv_map.read_text(encoding='utf-8')) if args.csv_map else None

    import psycopg2
    # SQLAlchemy-style URLs are what the rest of this project uses; psycopg2
    # needs the driver suffix removed.
    url = args.db_url.replace('postgresql+psycopg2://', 'postgresql://', 1)

    # publish() owns the transaction. Do not enter the connection context here:
    # psycopg2 rejects recursive connection context entry.
    conn = psycopg2.connect(url)
    try:
        # master_rows is lazy: its SELECT runs when publish() iterates rows,
        # after the publication transaction and advisory lock have begun.
        rows = master_rows(conn) if args.from_master else csv_rows(args.csv, mapping)
        result = publish(
            conn,
            dataset_id=args.dataset_id, source_id=args.source_id,
            slope_method=args.slope_method, boundary_date=args.boundary_date,
            rows=rows, activate=args.activate, dry_run=args.dry_run,
            nodata=args.nodata_value, expected_stops=args.expected_stops,
            expected_at_least=args.expected_at_least,
            reference_slope=args.reference_slope, processed_at=processed_at,
        )
    finally:
        conn.close()

    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except PublishError as exc:
        raise SystemExit(f'Publication rejected: {exc}')
