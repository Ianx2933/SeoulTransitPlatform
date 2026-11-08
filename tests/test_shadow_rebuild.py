"""The weekly rebuild writes __next copies, validates them, and only then swaps them in.

Updating the tables in place had two costs (2026-11-08): the API served rebuilt
numbers before validation had run, and each rebuild left the tables up to twice
their size. These tests pin the parts of the replacement that run without a
database: which table each step writes and reads, what validation looks at, and
the statements that swap the copies in while keeping index names.
"""

import io
import sys
from pathlib import Path

import pandas as pd
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "pipelines" / "hourly_stop_pattern"))

from pipelines.hourly_od_estimation import common as od_common  # noqa: E402
from pipelines.hourly_od_estimation import estimate_hourly_od as od  # noqa: E402
from pipelines.hourly_od_estimation import publish_rebuild as publish  # noqa: E402
from pipelines.hourly_od_estimation import solve_dow_hourly_ratio as ratio  # noqa: E402
from pipelines.hourly_od_estimation import validate_rebuild as validate  # noqa: E402
import common as stop_common  # noqa: E402

COMMONS = [od_common, stop_common]

# Index definitions as Cloud SQL reported them on 2026-11-08, and the names
# PostgreSQL gives the copies made with CREATE TABLE ... (LIKE ... INCLUDING ALL).
LIVE = {
    "integrated_hourly_transit_demand_light_pkey":
        "CREATE UNIQUE INDEX integrated_hourly_transit_demand_light_pkey ON public.integrated_hourly_transit_demand_light USING btree (mode, service_id, node_id, day_type, hour)",
    "idx_ihtdl_bus_norm_node_id":
        "CREATE INDEX idx_ihtdl_bus_norm_node_id ON public.integrated_hourly_transit_demand_light USING btree (lpad((node_id)::text, 5, '0'::text)) WHERE ((mode)::text = 'bus'::text)",
    "idx_ihtdl_mode_day_hour_service_node":
        "CREATE INDEX idx_ihtdl_mode_day_hour_service_node ON public.integrated_hourly_transit_demand_light USING btree (mode, day_type, hour, service_id, node_id)",
    "idx_integrated_hourly_transit_demand_light_mode_hour":
        "CREATE INDEX idx_integrated_hourly_transit_demand_light_mode_hour ON public.integrated_hourly_transit_demand_light USING btree (mode, day_type, hour)",
}
NEXT = {
    "integrated_hourly_transit_demand_light__next_pkey":
        "CREATE UNIQUE INDEX integrated_hourly_transit_demand_light__next_pkey ON public.integrated_hourly_transit_demand_light__next USING btree (mode, service_id, node_id, day_type, hour)",
    "integrated_hourly_transit_demand_light__next_lpad_idx":
        "CREATE INDEX integrated_hourly_transit_demand_light__next_lpad_idx ON public.integrated_hourly_transit_demand_light__next USING btree (lpad((node_id)::text, 5, '0'::text)) WHERE ((mode)::text = 'bus'::text)",
    "integrated_hourly_transit_dem_mode_day_type_hour_service_id_idx":
        "CREATE INDEX integrated_hourly_transit_dem_mode_day_type_hour_service_id_idx ON public.integrated_hourly_transit_demand_light__next USING btree (mode, day_type, hour, service_id, node_id)",
    "integrated_hourly_transit_demand_light___mode_day_type_hour_idx":
        "CREATE INDEX integrated_hourly_transit_demand_light___mode_day_type_hour_idx ON public.integrated_hourly_transit_demand_light__next USING btree (mode, day_type, hour)",
}
TABLE = "integrated_hourly_transit_demand_light"


@pytest.mark.parametrize("module", COMMONS, ids=["od", "stop"])
def test_target_comes_from_the_argument_then_the_environment(module, monkeypatch):
    monkeypatch.delenv(module.TABLE_TARGET_ENV, raising=False)
    assert module.table_target() == "live"
    monkeypatch.setenv(module.TABLE_TARGET_ENV, "next")
    assert module.table_target() == "next"
    assert module.table_target("live") == "live"
    assert module.target_table("dow_hourly_ratio", "next") == "dow_hourly_ratio__next"
    assert module.target_table("dow_hourly_ratio", "live") == "dow_hourly_ratio"
    monkeypatch.setenv(module.TABLE_TARGET_ENV, "nxt")
    with pytest.raises(SystemExit):
        module.table_target()


def test_every_rebuilt_index_finds_its_live_name():
    pairs = dict(publish.match_indexes(LIVE, NEXT))
    assert pairs == {
        "integrated_hourly_transit_demand_light__next_pkey": "integrated_hourly_transit_demand_light_pkey",
        "integrated_hourly_transit_demand_light__next_lpad_idx": "idx_ihtdl_bus_norm_node_id",
        "integrated_hourly_transit_dem_mode_day_type_hour_service_id_idx": "idx_ihtdl_mode_day_hour_service_node",
        "integrated_hourly_transit_demand_light___mode_day_type_hour_idx": "idx_integrated_hourly_transit_demand_light_mode_hour",
    }


def test_a_lost_or_changed_index_stops_the_swap():
    fewer = dict(list(NEXT.items())[:-1])
    with pytest.raises(ValueError, match="missing from the rebuilt table"):
        publish.match_indexes(LIVE, fewer)
    changed = dict(NEXT)
    changed["x"] = changed.pop("integrated_hourly_transit_demand_light___mode_day_type_hour_idx").replace(
        "(mode, day_type, hour)", "(mode, hour)")
    with pytest.raises(ValueError, match="matches no live index"):
        publish.match_indexes(LIVE, changed)


def test_swap_renames_then_drops_then_restores_index_names():
    statements = publish.plan_swap(TABLE, LIVE, NEXT)
    assert statements[:3] == [
        f'ALTER TABLE "{TABLE}" RENAME TO "{TABLE}__old"',
        f'ALTER TABLE "{TABLE}__next" RENAME TO "{TABLE}"',
        f'DROP TABLE "{TABLE}__old"',
    ]
    assert sorted(s.split(" RENAME TO ")[1].strip('"') for s in statements[3:]) == sorted(LIVE)


class Result:
    """Enough of a SQLAlchemy result for the validation checks to run."""

    class Row:
        def __getattr__(self, name):
            return 1

    def one(self):
        return self.Row()

    def scalar(self):
        return 1

    def all(self):
        return []

    def __iter__(self):
        return iter([])

    def close(self):
        pass


class RecordingConnection:
    def __init__(self):
        self.sql, self.copied, self.params = [], [], []
        outer = self

        class Cursor:
            def copy_expert(self, statement, buffer):
                outer.sql.append(statement)
                outer.copied.append(buffer.read())

            def close(self):
                pass

        class Driver:
            def cursor(self):
                return Cursor()

        self.connection = Driver()

    def execute(self, statement, params=None):
        self.sql.append(str(statement))
        # The estimator clears and reuses its buffer, so keep a copy of the rows.
        self.params.append([dict(r) for r in params] if isinstance(params, list) else params)
        return Result()

    def exec_driver_sql(self, statement):
        self.sql.append(statement)

    def execution_options(self, **options):
        return self


def run_all_checks(conn):
    report = validate.Report()
    validate.check_tables_not_empty(conn, report)
    validate.check_ratio_sums(conn, report)
    validate.check_ars_codes(conn, report)
    validate.check_od_totals(conn, report, "20251111", "20251111")
    validate.check_method_breakdown(conn, report, "20251111", "20251111")
    validate.check_no_duplicate_methods(conn, report, "20251111", "20251111")
    validate.print_fingerprint(conn, "20251111", "20251111")
    return report


def test_validation_of_the_next_target_reads_only_the_copies(capsys):
    conn = RecordingConnection()
    validate.use_target("next")
    try:
        report = run_all_checks(conn)
    finally:
        validate.use_target("live")
    sql = "\n".join(conn.sql)

    for table in validate.REBUILT_TABLES:
        assert f"{table}__next" in sql
        assert sql.count(table) == sql.count(f"{table}__next")    # never unsuffixed
    # Sources stay as they are: the copies are checked against the real inputs.
    assert "FROM hourly_bus_stop_passenger" in sql and "FROM analysis_table_final" in sql
    assert "hourly_bus_stop_passenger__next" not in sql and "analysis_table_final__next" not in sql
    assert any("dow_hourly_ratio__next" in name for name, _, _ in report.rows)


def test_validation_of_the_live_target_is_unchanged(capsys):
    conn = RecordingConnection()
    run_all_checks(conn)
    assert "__next" not in "\n".join(conn.sql)


def test_prepare_next_table_copies_structure_and_kept_rows():
    conn = RecordingConnection()
    name = od_common.prepare_next_table(conn, "estimated_hourly_od",
                                        keep_where="NOT (기준일자 >= :start_date)",
                                        params={"start_date": "20251111"})
    assert name == "estimated_hourly_od__next"
    assert conn.sql == [
        "DROP TABLE IF EXISTS estimated_hourly_od__next",
        "CREATE TABLE estimated_hourly_od__next (LIKE estimated_hourly_od INCLUDING ALL)",
        "INSERT INTO estimated_hourly_od__next SELECT * FROM estimated_hourly_od "
        "WHERE NOT (기준일자 >= :start_date)",
    ]


def test_copy_frame_writes_every_row_in_chunks():
    frame = pd.DataFrame({"a": list(range(7)), "b": [x / 3 for x in range(7)]})
    conn = RecordingConnection()
    assert od_common.copy_frame(conn, frame, "t__next", ["a", "b"], chunk_rows=3) == 7
    assert conn.sql == ["COPY t__next (a, b) FROM STDIN WITH (FORMAT csv)"] * 3
    back = pd.read_csv(io.StringIO("".join(conn.copied)), header=None, names=["a", "b"],
                       float_precision="round_trip")
    pd.testing.assert_frame_equal(back, frame)


def test_solver_appends_to_the_copy_and_upserts_the_table():
    frame = pd.DataFrame([{c: 1 for c in ratio.RATIO_COLUMNS}])

    class Engine:
        def __init__(self):
            self.conn = RecordingConnection()

        def begin(self):
            engine = self

            class Context:
                def __enter__(self):
                    return engine.conn

                def __exit__(self, *exc):
                    return False

            return Context()

    next_engine, live_engine = Engine(), Engine()
    ratio.write_batch(next_engine, frame, "dow_hourly_ratio__next", upsert=False)
    ratio.write_batch(live_engine, frame)
    assert next_engine.conn.sql == [
        f"COPY dow_hourly_ratio__next ({', '.join(ratio.RATIO_COLUMNS)}) FROM STDIN WITH (FORMAT csv)"]
    assert any("ON CONFLICT" in s for s in live_engine.conn.sql)


def test_the_estimator_hands_the_same_rows_to_a_writer():
    daily_od = pd.DataFrame([{"기준일자": "20251111", "노선명": "146", "승차_정류장ars": "07616",
                              "하차_정류장ars": "01001", "daily_passengers": 100.0}])
    ratio_map = {("146", "07616", "tue"): [1 / 24] * 24}

    upserted = RecordingConnection()
    od.estimate(None, daily_od, ratio_map, set(), set(), batch_size=10, conn=upserted)
    written = []
    od.estimate(None, daily_od, ratio_map, set(), set(), batch_size=10,
                writer=lambda records: written.extend(dict(r) for r in records))

    upsert_rows = [dict(r) for batch in upserted.params for r in batch]
    assert written == upsert_rows and len(written) == 24
    assert list(written[0]) == od.OD_COLUMNS
    assert sum(r["estimated_passengers"] for r in written) == pytest.approx(100.0)
