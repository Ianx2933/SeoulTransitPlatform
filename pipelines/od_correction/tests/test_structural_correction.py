"""The pandas port of the Java odcorrection workflow.

Two kinds of test:

* Behaviour tests (always run): each Java step on a small table, the
  deduplicateAndSum defects reproduced in mode=faithful, and fixed in
  mode=fixed with passengers conserved.
* Equivalence tests (run when a PostgreSQL server is reachable): random tables
  go through sql/java_correction_steps.sql via run_java_baseline.py and through
  mode=faithful, and the outputs and step counts must be identical.

  Set OD_BASELINE_DSN (libpq string) and, if psql is not on PATH,
  OD_BASELINE_PSQL. Only temp tables are used, in a rolled-back transaction.
"""

import os
import random
import shutil
import sys
from pathlib import Path

import pandas as pd
import pytest

SRC = Path(__file__).resolve().parents[1] / "src"
sys.path.insert(0, str(SRC))

import compare_corrections as cmp  # noqa: E402
import od_structural_correction as sc  # noqa: E402
import run_java_baseline as baseline  # noqa: E402

DATE = "260915"


def row(route="100", rid="R1", bseq=1, bars="01001", bcode="100000001", bname="A",
        aseq=2, aars="01002", acode="100000002", aname="B", pax=1, date=DATE):
    return dict(zip(sc.COLUMNS, [date, route, rid, bseq, bars, bcode, bname, aseq, aars, acode, aname, pax]))


def frame(*rows):
    return sc.normalize_types(pd.DataFrame(list(rows), columns=sc.COLUMNS))


def run(rows, mode="fixed"):
    return sc.correct(frame(*rows), mode)


def step(report, name):
    return next(s for s in report["steps"] if s["step"] == name)["tags"]


# --------------------------------------------------------------------------- #
# Individual steps
# --------------------------------------------------------------------------- #

def test_virtual_stop_names_get_ars_00000_except_06137():
    out, _, report = run([
        row(bname="기점가상", bars="12345", bseq=None, aseq=3),
        row(bname="경유지", bars="06137", bseq=4, aseq=5),
        row(bname="정류장", bars="01001", bseq=6, aseq=7),
        row(bname="가상정류장", bars=None, bseq=None, aseq=8),
    ], "faithful")
    assert step(report, "fixVirtualStopBoardingArs") == [["UPDATE", 2]]
    assert sorted(out[sc.B_ARS].astype(str)) == ["00000", "00000", "01001", "06137"]


def test_virtual_alighting_cases_a_c1_c2_b():
    rows = [row(bseq=s, bars=f"{s:05d}", aseq=s + 1, aars=f"{s + 1:05d}", pax=1) for s in range(1, 30)]
    rows += [
        row(bname="가상", bars="00000", bseq=None, aname="가상", aars="00000", aseq=None, pax=10),  # A
        row(bseq=5, bars="00005", aname="가상", aars="99999", aseq=None, pax=20),                 # C1
        row(bseq=12, bars="00012", aname="가상", aars="99999", aseq=None, pax=30),                # C2: 12/29 < 48%
        row(bseq=20, bars="00020", aname="가상", aars="99999", aseq=None, pax=40),                # B
    ]
    out, anomaly, report = run(rows, "faithful")
    assert step(report, "fixAlightingSequenceCaseA") == [["UPDATE", 1]]
    assert step(report, "fixAlightingSequenceCaseC1") == [["UPDATE", 1]]
    assert step(report, "insertAnomalyDataCaseC2") == [["INSERT", 1]]
    assert step(report, "fixAlightingSequenceCaseB") == [["UPDATE", 1]]
    assert anomaly[sc.PAX].tolist() == [30]
    assert report["passengers"]["input"] == report["passengers"]["output"] + 30


def test_most_frequent_boarding_sequence_wins_and_ties_take_the_smallest():
    out, _, report = run([
        row(bseq=3, aseq=9, aars="09999"), row(bseq=3, aseq=10, aars="09998"),
        row(bseq=4, aseq=11, aars="09997"),
        row(bars="02002", bseq=7, aseq=12, aars="09996"), row(bars="02002", bseq=6, aseq=13, aars="09995"),
    ], "faithful")
    by_ars = out.groupby(sc.B_ARS)[sc.B_SEQ].unique()
    assert list(by_ars["01001"]) == [3]
    assert list(by_ars["02002"]) == [6]          # D1: tie 6 vs 7 -> 6
    assert step(report, "fixSequenceByArs") == [["UPDATE", 2]]


def test_same_stop_od_moves_to_the_next_stop():
    out, _, _ = run([
        row(bseq=1, bars="01001", aseq=1, aars="01001", aname="A", pax=5),
        row(bseq=2, bars="01002", bcode="100000002", bname="B", aseq=3, aars="01003", pax=1),
    ], "fixed")
    moved = out[out[sc.PAX] == 5].iloc[0]
    assert (moved[sc.A_SEQ], moved[sc.A_ARS], moved[sc.A_NAME]) == (2, "01002", "B")


def test_nulls_never_match():
    # NULL ARS: not a virtual stop, not a same-stop OD, not merged on ARS.
    out, _, _ = run([row(bars=None, aars=None, bseq=None, aseq=None, pax=3)], "faithful")
    assert out[sc.B_SEQ].isna().all() and out[sc.A_SEQ].isna().all()


# --------------------------------------------------------------------------- #
# deduplicateAndSum: defects in faithful, fixed in fixed
# --------------------------------------------------------------------------- #

def test_faithful_merge_drops_the_boarding_code():
    out, _, _ = run([row(pax=3), row(pax=4)], "faithful")
    assert len(out) == 1 and out[sc.PAX].iloc[0] == 7
    assert out[sc.B_CODE].isna().all()


def test_faithful_merge_deletes_rows_whose_name_differs():
    # Same key, one row has another alighting name: grouped apart, deleted together.
    out, _, report = run([row(pax=3), row(pax=4), row(aname="B (변경)", pax=5)], "faithful")
    assert int(out[sc.PAX].sum()) == 7
    assert report["passenger_diff"] == -5


def test_faithful_merge_with_null_route_id_doubles_on_every_pass():
    # The NULL rows are never deleted, so each of the three passes adds the sum
    # of everything already there: 3+4 -> 3,4,7 -> 3,4,7,14 -> 3,4,7,14,28.
    out, _, report = run([row(rid=None, pax=3), row(rid=None, pax=4)], "faithful")
    assert sorted(out[sc.PAX].tolist()) == [3, 4, 7, 14, 28]
    assert int(out[sc.PAX].sum()) == 56          # 8x
    assert report["passenger_diff"] == 49


def test_fixed_merges_on_the_key_and_keeps_every_passenger():
    out, _, report = run([
        row(pax=3), row(pax=4), row(aname="B (변경)", pax=5),
        row(rid=None, pax=1), row(rid=None, pax=2),
    ], "fixed")
    assert report["passenger_diff"] == 0
    merged = out[out[sc.ROUTE_ID].notna()].iloc[0]
    assert merged[sc.PAX] == 12 and merged[sc.B_CODE] == "100000001"
    assert merged[sc.A_NAME] == "B (변경)"          # the row with most passengers
    assert int(out.loc[out[sc.ROUTE_ID].isna(), sc.PAX].iloc[0]) == 3
    tags = dict((k, v) for k, v in step(report, "dedup.virtual"))
    assert tags == {"MERGED_GROUPS": 2, "MERGED_ROWS": 5, "CONFLICTS": 1}


def test_fixed_keeps_stops_without_ars_apart_by_name():
    # 2026-09-15: 61 ferry rows with no route, ARS or sequence were merged into
    # one row of 2,444 passengers before stops without an ARS kept their names.
    ferry = dict(route=None, rid=None, bseq=None, bars=None, bcode=None, aseq=None, aars=None, acode=None)
    out, _, report = run([
        row(**ferry, bname="뚝섬", aname="여의도", pax=204),
        row(**ferry, bname="뚝섬", aname="잠실", pax=107),
        row(**ferry, bname="마곡", aname="여의도", pax=107),
        row(**ferry, bname="마곡", aname="여의도", pax=3),
    ], "fixed")
    pairs = sorted(zip(out[sc.B_NAME], out[sc.A_NAME], out[sc.PAX]))
    assert pairs == sorted([("마곡", "여의도", 110), ("뚝섬", "여의도", 204), ("뚝섬", "잠실", 107)])
    assert report["passenger_diff"] == 0


def test_fixed_mode_fails_when_passengers_are_lost(monkeypatch):
    def lose_one(r, date):
        r.frame = r.frame.iloc[1:]
    monkeypatch.setattr(sc, "sequence_steps", lose_one)
    with pytest.raises(sc.ConservationError):
        run([row(pax=1), row(bseq=3, bars="01003", pax=2)], "fixed")


def test_cli_round_trip(tmp_path):
    source = tmp_path / "in.csv"
    sc.write_od(frame(row(pax=3), row(pax=4), row(bname="가상", bars="09999", bseq=None, pax=2)), source)
    sc.main(["--input", str(source), "--output", str(tmp_path / "out.csv"),
             "--anomaly", str(tmp_path / "an.csv"), "--report", str(tmp_path / "r.json")])
    again = sc.read_od(tmp_path / "out.csv")
    assert int(again[sc.PAX].sum()) == 9


# --------------------------------------------------------------------------- #
# Equivalence with the Java SQL on PostgreSQL
# --------------------------------------------------------------------------- #

DSN = os.environ.get("OD_BASELINE_DSN")
PSQL = os.environ.get("OD_BASELINE_PSQL") or shutil.which("psql")
needs_pg = pytest.mark.skipif(not (DSN and PSQL), reason="set OD_BASELINE_DSN to run against PostgreSQL")


def random_rows(seed, n=60):
    rnd = random.Random(seed)
    names = ["A", "B", "C", "가상", "기점가상", "경유", "", None, "D역"]
    ars = ["01001", "01002", "01003", "00000", "06137", None, "02002"]
    codes = ["100000001", "100000002", sc.EMPTY_NAME_CODE, None]
    rows = []
    for _ in range(n):
        rows.append(row(
            route=rnd.choice(["100", "200", None]) if rnd.random() < 0.05 else rnd.choice(["100", "200"]),
            rid=rnd.choice(["R1", "R1", "R1", None]),
            bseq=rnd.choice([None] + list(range(0, 31))),
            bars=rnd.choice(ars), bcode=rnd.choice(codes), bname=rnd.choice(names),
            aseq=rnd.choice([None] + list(range(0, 31))),
            aars=rnd.choice(ars), acode=rnd.choice(codes), aname=rnd.choice(names),
            pax=rnd.randint(1, 9),
        ))
        if rnd.random() < 0.3:              # exact and near duplicates
            dup = dict(rows[rnd.randrange(len(rows))])
            if rnd.random() < 0.3:
                dup[sc.A_NAME] = rnd.choice(names)
            rows.append(dup)
    return rows


@needs_pg
@pytest.mark.parametrize("seed", range(150))
def test_faithful_matches_the_java_sql(tmp_path, seed):
    source = tmp_path / "in.csv"
    sc.write_od(frame(*random_rows(seed)), source)

    steps = baseline.run(PSQL, DSN, source, tmp_path / "java.csv", tmp_path / "java_an.csv")
    out, anomaly, report = sc.correct(sc.read_od(source), "faithful")
    sc.write_od(out, tmp_path / "py.csv")
    sc.write_od(anomaly, tmp_path / "py_an.csv")

    for java, py in (("java.csv", "py.csv"), ("java_an.csv", "py_an.csv")):
        only_java, only_py = cmp.compare(cmp.read_text(tmp_path / java), cmp.read_text(tmp_path / py))
        assert not only_java and not only_py, (java, list(only_java)[:3], list(only_py)[:3])
    assert [(s["step"], s["tags"]) for s in steps] == [(s["step"], s["tags"]) for s in report["steps"]]


@pytest.mark.parametrize("seed", range(150))
def test_fixed_conserves_passengers_on_random_tables(seed):
    _, _, report = sc.correct(frame(*random_rows(seed)), "fixed")
    assert report["passenger_diff"] == 0
