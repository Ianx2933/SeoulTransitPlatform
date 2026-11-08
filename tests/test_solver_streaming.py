"""The solvers read the monthly table once and still solve the same batches.

Until 2026-11 each batch of routes ran its own query, which on Cloud SQL meant
finding a few routes' rows scattered across every month of a 30-million-row
table, 29 times per run. The table is now read once, ordered by route, and cut
into the same batches. These tests pin that the batches, the solutions and the
rows written are what the per-batch version produced.
"""

import io
import sys
from pathlib import Path

import numpy as np
import pandas as pd
import pytest
from sqlalchemy.exc import OperationalError

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pipelines" / "hourly_stop_pattern"))

from pipelines.hourly_od_estimation import solve_dow_hourly_ratio as ratio  # noqa: E402
import solve_dow_hourly_stop_pattern as stop_pattern  # noqa: E402

SOLVERS = [ratio, stop_pattern]
IDS = ["ratio", "stop_pattern"]
MONTHS = ["202401", "202402", "202403", "202404", "202405", "202406"]
ROUTES = ["100", "101", "102", "103", "104", "105", "106"]   # 103 has no rows


def month_counts():
    rng = np.random.default_rng(7)
    counts = rng.integers(3, 6, size=(len(MONTHS), 7))
    frame = pd.DataFrame(counts, columns=ratio.DAY_TYPES)
    frame.insert(0, "use_ym", MONTHS)
    return frame


def hourly_rows(module, seed=11):
    """Monthly hourly rows for every route but 103, some groups with few months."""
    rng = np.random.default_rng(seed)
    rows = []
    for route in ROUTES:
        if route == "103":
            continue
        for stop in ["01001", "01002", "02003"]:
            for hour in range(0, 24, 3):
                observed = MONTHS if rng.random() > 0.2 else MONTHS[:2]
                for ym in observed:
                    rows.append({
                        "use_ym": ym, "route_no": route, "stop_ars": stop, "hour": hour,
                        "monthly_boarding": float(rng.integers(0, 5000)),
                        "monthly_alighting": float(rng.integers(0, 5000)),
                    })
    frame = pd.DataFrame(rows)
    return frame[module.HOURLY_COLUMNS]


def route_ordered(frame, seed=3):
    """Ordered by route only, as ORDER BY route_no returns it; any order within a route."""
    shuffled = frame.sample(frac=1, random_state=seed)
    return shuffled.sort_values("route_no", kind="stable").reset_index(drop=True)


def chunked(frame, size):
    for start in range(0, len(frame), size):
        yield frame.iloc[start:start + size]


def per_batch_version(module, frame, route_batch, start_index=0):
    """What the previous version did: one query per routes[s:s + route_batch]."""
    result = []
    for start in range(start_index, len(ROUTES), route_batch):
        batch = ROUTES[start:start + route_batch]
        rows = frame[frame["route_no"].isin(batch)].sample(frac=1, random_state=start)
        result.append((start, module.solve_patterns(month_counts(), rows, min_months=3)))
    return result


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
@pytest.mark.parametrize("route_batch, chunk_size, start_index", [
    (2, 7, 0), (3, 50, 0), (1, 1, 0), (25, 13, 0), (3, 11, 2),
])
def test_streamed_batches_solve_exactly_as_the_per_batch_queries(
        module, route_batch, chunk_size, start_index):
    frame = hourly_rows(module)
    stream_input = route_ordered(frame[frame["route_no"].map(ROUTES.index) >= start_index])

    expected = per_batch_version(module, frame, route_batch, start_index)
    actual = [
        (start, module.solve_patterns(month_counts(), rows, min_months=3))
        for start, rows in module.route_batches(
            chunked(stream_input, chunk_size), ROUTES, route_batch, start_index)
    ]

    assert [s for s, _ in actual] == [s for s, _ in expected]
    for (_, got), (_, want) in zip(actual, expected):
        pd.testing.assert_frame_equal(got.reset_index(drop=True), want.reset_index(drop=True),
                                      check_exact=True)


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_a_batch_without_rows_is_still_yielded(module):
    frame = route_ordered(hourly_rows(module))
    starts = [s for s, rows in module.route_batches(chunked(frame, 5), ROUTES, 1)
              if rows.empty]
    assert starts == [ROUTES.index("103")]


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_rows_out_of_route_order_are_refused(module):
    frame = route_ordered(hourly_rows(module)).iloc[::-1]
    with pytest.raises(ValueError, match="not ordered"):
        list(module.route_batches(chunked(frame, 9), ROUTES, 2))


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_a_route_missing_from_the_route_list_is_refused(module):
    frame = route_ordered(hourly_rows(module))
    with pytest.raises(ValueError, match="not in the route list"):
        list(module.route_batches(chunked(frame, 9), ROUTES[:-1], 2))


class FakeEngine:
    def __init__(self):
        self.disposed = 0

    def dispose(self):
        self.disposed += 1


def dropped():
    return OperationalError("SELECT ...", {}, Exception("server closed the connection unexpectedly"))


def fake_stream(frame, drops=(), drop_at=None):
    """Serve route-ordered chunks for start_route..end_route; raise at chunk drop_at while drops remain."""
    calls, drops = [], list(drops)

    def stream(engine, start_route, end_route, chunk_rows, work_mem):
        calls.append((start_route, end_route))
        pos = frame["route_no"].map(ROUTES.index)
        rows = frame[(pos >= ROUTES.index(start_route)) & (pos <= ROUTES.index(end_route))]
        for n, chunk in enumerate(chunked(rows.reset_index(drop=True), 10)):
            if drops and n == drop_at:
                drops.pop()
                raise dropped()
            yield chunk

    return stream, calls


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_a_dropped_read_resumes_at_the_first_unwritten_batch(module, capsys):
    frame = route_ordered(hourly_rows(module))
    first_batch_rows = int(frame["route_no"].isin(ROUTES[:2]).sum())
    # The chunk holding the first row of routes 102-103 completes batch 0, which is
    # written; the read then drops on the next chunk, inside batch 1.
    stream, calls = fake_stream(frame, drops=[1], drop_at=first_batch_rows // 10 + 1)
    written, waits, engine = [], [], FakeEngine()

    module.run(engine, ROUTES, month_counts(), route_batch=2, min_months=3,
               routes_per_read=100, stream=stream, write=lambda e, df: written.append(df),
               sleep=waits.append)

    assert calls == [("100", "106"), ("102", "106")]
    assert engine.disposed == 1 and waits == [module.RETRY_WAIT_SECONDS]
    expected = [df for _, df in per_batch_version(module, frame, 2)]
    assert len(written) == len(expected)
    for got, want in zip(written, expected):
        pd.testing.assert_frame_equal(got.reset_index(drop=True), want.reset_index(drop=True),
                                      check_exact=True)
    assert "connection lost" in capsys.readouterr().out


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_the_run_gives_up_after_drops_in_a_row(module):
    frame = route_ordered(hourly_rows(module))

    def stream(engine, start_route, end_route, chunk_rows, work_mem):
        raise dropped()
        yield  # pragma: no cover

    engine = FakeEngine()
    with pytest.raises(OperationalError):
        module.run(engine, ROUTES, month_counts(), route_batch=2, min_months=3,
                   stream=stream, write=lambda e, df: None, attempts=3, sleep=lambda s: None)
    assert engine.disposed == 2


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_a_server_limit_is_not_retried(module):
    """temp_file_limit on 2026-11-08: waiting and repeating the same sort fails again."""
    class LimitExceeded(Exception):
        pgcode = "53400"

    def stream(engine, start_route, end_route, chunk_rows, work_mem):
        raise OperationalError("SELECT ...", {}, LimitExceeded("temporary file size exceeds"))
        yield  # pragma: no cover

    engine, waits = FakeEngine(), []
    with pytest.raises(OperationalError):
        module.run(engine, ROUTES, month_counts(), route_batch=2, min_months=3,
                   stream=stream, write=lambda e, df: None, sleep=waits.append)
    assert waits == [] and engine.disposed == 0


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
@pytest.mark.parametrize("routes_per_read, expected_reads", [
    (3, [("100", "103"), ("104", "106")]),      # rounded up to two batches of 2
    (2, [("100", "101"), ("102", "103"), ("104", "105"), ("106", "106")]),
    (100, [("100", "106")]),
])
def test_reads_are_windows_of_whole_batches_with_the_same_results(
        module, routes_per_read, expected_reads):
    frame = route_ordered(hourly_rows(module))
    stream, calls = fake_stream(frame)
    written = []

    module.run(FakeEngine(), ROUTES, month_counts(), route_batch=2, min_months=3,
               routes_per_read=routes_per_read, stream=stream,
               write=lambda e, df: written.append(df))

    assert calls == expected_reads
    expected = [df for _, df in per_batch_version(module, frame, 2)]
    assert len(written) == len(expected)
    for got, want in zip(written, expected):
        pd.testing.assert_frame_equal(got.reset_index(drop=True), want.reset_index(drop=True),
                                      check_exact=True)


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_a_drop_in_a_later_window_resumes_inside_that_window(module):
    frame = route_ordered(hourly_rows(module))
    rows_before_104 = int(frame["route_no"].isin(ROUTES[:4]).sum())
    rows_104_105 = int(frame["route_no"].isin(ROUTES[4:6]).sum())
    # Second window (routes 104-106) drops after its first batch (104-105) is written.
    stream, calls = fake_stream(frame, drops=[1], drop_at=None)
    seen = {"n": 0}

    def flaky(engine, start_route, end_route, chunk_rows, work_mem):
        seen["n"] += 1
        inner = stream(engine, start_route, end_route, chunk_rows, work_mem)
        for i, chunk in enumerate(inner):
            if seen["n"] == 2 and i == rows_104_105 // 10 + 1:
                raise dropped()
            yield chunk

    written = []
    module.run(FakeEngine(), ROUTES, month_counts(), route_batch=2, min_months=3,
               routes_per_read=4, stream=flaky, write=lambda e, df: written.append(df),
               sleep=lambda s: None)

    assert rows_before_104 > 0
    assert calls == [("100", "103"), ("104", "106"), ("106", "106")]
    assert len(written) == len(per_batch_version(module, frame, 2))


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_a_row_past_the_window_is_refused(module):
    frame = route_ordered(hourly_rows(module))
    with pytest.raises(ValueError, match="outside the routes"):
        list(module.route_batches(chunked(frame, 9), ROUTES, 2, 0, 4))


class RecordingConnection:
    """Stands in for a SQLAlchemy connection inside engine.begin()."""

    def __init__(self):
        self.statements, self.copied = [], []
        outer = self

        class Cursor:
            def copy_expert(self, sql, buffer):
                outer.statements.append(sql)
                outer.copied.append(buffer.read())

            def close(self):
                pass

        class Driver:
            def cursor(self):
                return Cursor()

        self.connection = Driver()

    def exec_driver_sql(self, sql):
        self.statements.append(sql)


@pytest.mark.parametrize("module, table, columns", [
    (ratio, ratio.RATIO_TABLE, ratio.RATIO_COLUMNS),
    (stop_pattern, stop_pattern.PATTERN_TABLE, stop_pattern.PATTERN_COLUMNS),
], ids=IDS)
def test_copy_upsert_writes_exactly_the_solved_values(module, table, columns):
    frame = module.solve_patterns(month_counts(), hourly_rows(module), min_months=3)
    conn = RecordingConnection()

    module.copy_upsert(conn, frame, table, columns, ["route_no", "stop_ars", "day_type", "hour"])

    create, copy, insert = conn.statements
    assert create.startswith(f"CREATE TEMP TABLE _stage_{table} ON COMMIT DROP")
    assert copy.startswith(f"COPY _stage_{table} ({', '.join(columns)}) FROM STDIN")
    assert "ON CONFLICT (route_no, stop_ars, day_type, hour) DO UPDATE SET" in insert
    assert "route_no = EXCLUDED" not in insert and "method = EXCLUDED.method" in insert

    back = pd.read_csv(io.StringIO(conn.copied[0]), header=None, names=columns,
                       dtype={"route_no": str, "stop_ars": str},
                       float_precision="round_trip")   # parse exactly, as PostgreSQL does
    for column in columns:
        if frame[column].dtype.kind == "f":
            assert np.array_equal(back[column].to_numpy(), frame[column].to_numpy())
        else:
            assert back[column].astype(str).tolist() == frame[column].astype(str).tolist()


@pytest.mark.parametrize("module", SOLVERS, ids=IDS)
def test_copy_upsert_skips_an_empty_batch(module):
    conn = RecordingConnection()
    module.copy_upsert(conn, pd.DataFrame(), "t", ["a"], ["a"])
    assert conn.statements == []
