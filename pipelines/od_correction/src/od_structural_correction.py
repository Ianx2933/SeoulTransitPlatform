"""
Structural OD correction: the Java odcorrection workflow, ported to pandas.

Input is the output of od_correction_pipeline.py (standard codes filled), one
or more 기준일자. Each date is corrected on its own, as the Java service did.

The Java service (services/api-server/.../odcorrection) ran three groups in
order, each followed by deduplicateAndSum:

  virtual stops   ARS of 가상/기점가상/경유 stops -> 00000 (except 06137); virtual
                  boarding sequence -> 0; virtual alighting sequence by case
                  A / C1 / C2 (moved to the anomaly table) / B; empty alighting
                  name for code 277102436; trips to and from a virtual stop moved
                  onto the real stop; virtual->virtual at one position -> next
  sequences       boarding sequence -> the most frequent one for (route, ARS);
                  alighting sequences within 2 of each other -> the smallest
  same-stop OD    alighting moved to the next stop, by sequence then by ARS

Two modes:

  faithful  The Java behaviour exactly, including the deduplicateAndSum defects.
            Used only to prove the port matches the Java SQL (run_java_baseline.py
            + compare_corrections.py). Two places where PostgreSQL itself was not
            deterministic use the tie breaks documented in
            sql/java_correction_steps.sql (D1, D2).
  fixed     The same corrections, with duplicates merged on the OD key
            (date, route, route ID, boarding seq/ARS, alighting seq/ARS),
            NULL-safe, nothing dropped and nothing counted twice. Passengers in
            = passengers out + anomaly, per route; the run fails otherwise.

Missing values follow SQL: an empty CSV field is NULL, and a comparison with
NULL is never true.

    python pipelines/od_correction/src/od_structural_correction.py ^
        --input data/od/curated_codes_20260915.csv ^
        --output data/od/curated_20260915.csv ^
        --anomaly data/od/anomaly_20260915.csv --mode fixed
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd

DATE = "기준일자"
ROUTE = "노선명"
ROUTE_ID = "전환_노선ID"
B_SEQ, B_ARS, B_CODE, B_NAME = "승차_정류장순번", "승차_정류장ARS", "승차_정류장표준코드", "승차_정류장명"
A_SEQ, A_ARS, A_CODE, A_NAME = "하차_정류장순번", "하차_정류장ARS", "하차_정류장표준코드", "하차_정류장명"
PAX = "승객수"

COLUMNS = [DATE, ROUTE, ROUTE_ID, B_SEQ, B_ARS, B_CODE, B_NAME, A_SEQ, A_ARS, A_CODE, A_NAME, PAX]
TEXT_COLUMNS = [DATE, ROUTE, ROUTE_ID, B_ARS, B_CODE, B_NAME, A_ARS, A_CODE, A_NAME]
INT_COLUMNS = [B_SEQ, A_SEQ, PAX]

BOARDING = [B_SEQ, B_ARS, B_CODE, B_NAME]
ALIGHTING = [A_SEQ, A_ARS, A_CODE, A_NAME]

VIRTUAL = "00000"
VIRTUAL_EXCEPTION = "06137"
EMPTY_NAME_CODE = "277102436"

# deduplicateAndSum
DEDUP_COUNT_KEY = [ROUTE, ROUTE_ID, B_SEQ, B_ARS, B_NAME, A_SEQ, A_ARS, A_NAME]
DEDUP_GROUP_KEY = [ROUTE, ROUTE_ID, B_SEQ, B_ARS, B_NAME, A_SEQ, A_ARS, A_CODE, A_NAME]
DEDUP_DELETE_KEY = [DATE, ROUTE, ROUTE_ID, B_SEQ, B_ARS, A_SEQ, A_ARS]
# fixed mode merges on this, NULL-safe. A stop is its ARS, or its name when it
# has no ARS: rows with no route, ARS or sequence (the Han River ferry piers in
# 2026-09) must not collapse into one row.
OD_KEY = [DATE, ROUTE, ROUTE_ID, B_SEQ, "_b_stop", A_SEQ, "_a_stop"]

MODES = ("faithful", "fixed")


# --------------------------------------------------------------------------- #
# SQL semantics helpers
# --------------------------------------------------------------------------- #

def true(condition: pd.Series) -> pd.Series:
    """SQL WHERE: NULL counts as false."""
    return condition.fillna(False).astype(bool)


def eq(left, right) -> pd.Series:
    return true(left == right)


def ne(left, right) -> pd.Series:
    return true(left != right)


def like_any(series: pd.Series, *needles: str) -> pd.Series:
    result = pd.Series(False, index=series.index)
    for needle in needles:
        result |= true(series.str.contains(needle, regex=False))
    return result


def lookup(frame: pd.DataFrame, left_keys: list, table: pd.DataFrame, right_keys: list,
           values: list) -> pd.DataFrame:
    """
    For each row of ``frame``, the ``values`` of the ``table`` row whose
    ``right_keys`` equal the row's ``left_keys`` (SQL equality: a NULL key
    matches nothing). ``table`` must be unique on ``right_keys``. Adds
    ``_hit`` = True where a row matched.
    """
    right = table.dropna(subset=right_keys)[right_keys + values].copy()
    if right.duplicated(right_keys).any():
        raise AssertionError(f"lookup table is not unique on {right_keys}")
    right["_hit"] = True
    left = pd.DataFrame({key: frame[lk].to_numpy() for lk, key in zip(left_keys, right_keys)})
    for key in right_keys:
        left[key] = left[key].astype(right[key].dtype)
    left["_pos"] = np.arange(len(frame))
    merged = left.merge(right, on=right_keys, how="left").sort_values("_pos")
    merged.index = frame.index
    merged["_hit"] = merged["_hit"].notna()
    return merged[values + ["_hit"]]


def copy_columns(frame: pd.DataFrame, mask: pd.Series, targets: list, sources: list) -> None:
    """UPDATE SET target = source, all right-hand sides read before any write."""
    snapshot = frame.loc[mask, sources].copy()
    for target, source in zip(targets, sources):
        frame.loc[mask, target] = snapshot[source].astype(frame[target].dtype).to_numpy()


# --------------------------------------------------------------------------- #
# Loading and saving
# --------------------------------------------------------------------------- #

def read_od(path: Path) -> pd.DataFrame:
    frame = pd.read_csv(path, sep="|", encoding="utf-8-sig", dtype=str,
                        keep_default_na=False, na_values=[""])
    if list(frame.columns) != COLUMNS:
        raise ValueError(f"Unexpected columns in {path}: {list(frame.columns)}")
    return normalize_types(frame)


def normalize_types(frame: pd.DataFrame) -> pd.DataFrame:
    frame = frame[COLUMNS].copy()
    for column in TEXT_COLUMNS:
        frame[column] = frame[column].astype("string")
    for column in INT_COLUMNS:
        frame[column] = pd.to_numeric(frame[column], errors="raise").astype("Int64")
    return frame


def write_od(frame: pd.DataFrame, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    frame[COLUMNS].to_csv(path, sep="|", index=False, encoding="utf-8-sig", na_rep="")


# --------------------------------------------------------------------------- #
# Steps (one 기준일자 at a time)
# --------------------------------------------------------------------------- #

@dataclass
class DayResult:
    frame: pd.DataFrame
    anomaly: pd.DataFrame
    steps: list = field(default_factory=list)


def _record(result: DayResult, date, step: str, *tags) -> None:
    result.steps.append({"date": date, "step": step, "tags": [list(t) for t in tags]})


def route_max_boarding_seq(day: pd.DataFrame) -> pd.Series:
    """MAX(승차_정류장순번) per 노선명 over rows where it is not NULL."""
    present = day[day[B_SEQ].notna() & day[ROUTE].notna()]
    table = present.groupby(ROUTE, dropna=True)[B_SEQ].max().rename("최대순번").reset_index()
    found = lookup(day, [ROUTE], table, [ROUTE], ["최대순번"])
    return found["최대순번"].astype("Int64")


def virtual_stop_steps(r: DayResult, date) -> None:
    d = r.frame

    mask = like_any(d[B_NAME], "가상", "기점가상", "경유") & ~eq(d[B_ARS], VIRTUAL_EXCEPTION)
    d.loc[mask, B_ARS] = VIRTUAL
    _record(r, date, "fixVirtualStopBoardingArs", ("UPDATE", int(mask.sum())))

    mask = like_any(d[A_NAME], "가상", "기점가상", "경유") & ~eq(d[A_ARS], VIRTUAL_EXCEPTION)
    d.loc[mask, A_ARS] = VIRTUAL
    _record(r, date, "fixVirtualStopAlightingArs", ("UPDATE", int(mask.sum())))

    mask = eq(d[B_ARS], VIRTUAL) & d[B_SEQ].isna()
    d.loc[mask, B_SEQ] = 0
    _record(r, date, "fixBoardingSequence", ("UPDATE", int(mask.sum())))

    mask = eq(d[B_SEQ], 0) & eq(d[B_ARS], VIRTUAL) & eq(d[A_ARS], VIRTUAL) & d[A_SEQ].isna()
    d.loc[mask, A_SEQ] = 0
    _record(r, date, "fixAlightingSequenceCaseA", ("UPDATE", int(mask.sum())))

    mask = eq(d[A_ARS], VIRTUAL) & d[A_SEQ].isna() & true((d[B_SEQ] >= 1) & (d[B_SEQ] <= 10))
    copy_columns(d, mask, [A_NAME, A_ARS, A_CODE, A_SEQ], [B_NAME, B_ARS, B_CODE, B_SEQ])
    _record(r, date, "fixAlightingSequenceCaseC1", ("UPDATE", int(mask.sum())))

    maximum = route_max_boarding_seq(d)
    ratio = d[B_SEQ].astype("Float64").to_numpy(dtype=float, na_value=np.nan) / \
        maximum.astype("Float64").to_numpy(dtype=float, na_value=np.nan) * 100
    with np.errstate(divide="ignore", invalid="ignore"):
        below = pd.Series(ratio < 48, index=d.index)
    mask = eq(d[A_ARS], VIRTUAL) & d[A_SEQ].isna() & maximum.notna() & maximum.ne(0).fillna(False) \
        & below & true(d[B_SEQ] >= 11)
    anomaly = d.loc[mask].copy()
    r.anomaly = pd.concat([r.anomaly, anomaly], ignore_index=True)
    _record(r, date, "insertAnomalyDataCaseC2", ("INSERT", int(mask.sum())))
    d = d.loc[~mask].copy()
    _record(r, date, "deleteAnomalyDataCaseC2", ("DELETE", int(mask.sum())))

    maximum = route_max_boarding_seq(d)
    mask = eq(d[A_ARS], VIRTUAL) & d[A_SEQ].isna() & maximum.notna()
    d.loc[mask, A_SEQ] = (maximum[mask] + 1).astype("Int64")
    _record(r, date, "fixAlightingSequenceCaseB", ("UPDATE", int(mask.sum())))

    mask = (d[A_NAME].isna() | eq(d[A_NAME], "")) & eq(d[A_CODE], EMPTY_NAME_CODE)
    copy_columns(d, mask, [A_NAME], [B_NAME])
    _record(r, date, "fixEmptyArrivalStopName", ("UPDATE", int(mask.sum())))

    mask = ne(d[B_ARS], VIRTUAL) & eq(d[A_ARS], VIRTUAL) & d[A_SEQ].notna()
    copy_columns(d, mask, ALIGHTING, BOARDING)
    _record(r, date, "fixVirtualStopBoarding", ("UPDATE", int(mask.sum())))

    mask = eq(d[B_ARS], VIRTUAL) & ne(d[B_SEQ], 0)
    copy_columns(d, mask, BOARDING, ALIGHTING)
    _record(r, date, "fixMidVirtualStopBoarding", ("UPDATE", int(mask.sum())))

    mask = eq(d[B_ARS], VIRTUAL) & eq(d[A_ARS], VIRTUAL) & eq(d[B_SEQ], d[A_SEQ])
    d.loc[mask, A_SEQ] = (d.loc[mask, B_SEQ] + 1).astype("Int64")
    _record(r, date, "fixVirtualStopSameOD", ("UPDATE", int(mask.sum())))

    r.frame = d


def sequence_steps(r: DayResult, date) -> None:
    d = r.frame

    # Most frequent boarding sequence per (route, ARS); D1 tie break.
    sub = d[ne(d[B_ARS], VIRTUAL)]
    counts = sub.groupby([ROUTE, B_ARS, B_SEQ], dropna=False).size().rename("n").reset_index()
    counts = counts.sort_values([ROUTE, B_ARS, "n", B_SEQ], ascending=[True, True, False, True],
                                na_position="last", kind="mergesort")
    mode = counts.drop_duplicates([ROUTE, B_ARS], keep="first").rename(columns={B_SEQ: "최빈순번"})
    found = lookup(d, [ROUTE, B_ARS], mode, [ROUTE, B_ARS], ["최빈순번"])
    mask = found["_hit"] & ne(d[B_SEQ], found["최빈순번"])
    d.loc[mask, B_SEQ] = found.loc[mask, "최빈순번"].astype("Int64")
    _record(r, date, "fixSequenceByArs", ("UPDATE", int(mask.sum())))

    keys = [ROUTE, B_SEQ, B_ARS, A_ARS]
    sub = d[ne(d[B_ARS], VIRTUAL) & ne(d[A_ARS], VIRTUAL)]
    stats = sub.groupby(keys, dropna=False)[A_SEQ].agg(["nunique", "min", "max"]).reset_index()
    stats = stats[true((stats["nunique"] > 1) & ((stats["max"] - stats["min"]) <= 2))]
    stats = stats.rename(columns={"min": "최소하차순번"})
    found = lookup(d, keys, stats, keys, ["최소하차순번"])
    mask = found["_hit"] & ne(d[A_SEQ], found["최소하차순번"])
    d.loc[mask, A_SEQ] = found.loc[mask, "최소하차순번"].astype("Int64")
    _record(r, date, "fixAlightingSequenceByArs", ("UPDATE", int(mask.sum())))


def same_stop_steps(r: DayResult, date) -> None:
    d = r.frame

    mask = eq(d[B_SEQ], d[A_SEQ]) & eq(d[B_ARS], d[A_ARS]) & ne(d[B_ARS], VIRTUAL)
    d.loc[mask, A_SEQ] = (d.loc[mask, B_SEQ] + 1).astype("Int64")
    _record(r, date, "fixSameStopODBySequence", ("UPDATE", int(mask.sum())))

    # The stop at the next boarding position; D2 picks one when several share it.
    nxt = d.sort_values([ROUTE, B_SEQ, B_ARS, B_CODE, B_NAME], na_position="last", kind="mergesort")
    nxt = nxt.dropna(subset=[ROUTE, B_SEQ]).drop_duplicates([ROUTE, B_SEQ], keep="first")
    nxt = nxt[[ROUTE, B_SEQ, B_ARS, B_CODE, B_NAME]].rename(
        columns={B_ARS: "_ars", B_CODE: "_code", B_NAME: "_name"})
    target = (d[B_SEQ] + 1).astype("Int64")
    found = lookup(d.assign(_target=target), [ROUTE, "_target"], nxt, [ROUTE, B_SEQ],
                   ["_ars", "_code", "_name"])
    mask = found["_hit"] & eq(d[B_ARS], d[A_ARS])
    d.loc[mask, A_SEQ] = target[mask]
    for column, source in ((A_ARS, "_ars"), (A_CODE, "_code"), (A_NAME, "_name")):
        d.loc[mask, column] = found.loc[mask, source].astype("string")
    _record(r, date, "fixSameStopODByArs", ("UPDATE", int(mask.sum())))


def dedup_faithful(r: DayResult, date, label: str) -> None:
    """OdCorrectionJdbcRepository.deduplicateAndSum, defects included."""
    d = r.frame
    sizes = d.groupby(DEDUP_COUNT_KEY, dropna=False).size()
    if int((sizes > 1).sum()) == 0:
        _record(r, date, label)
        return

    grouped = d.groupby(DEDUP_GROUP_KEY, dropna=False)
    temp = pd.concat({DATE: grouped[DATE].min(), PAX: grouped[PAX].sum(min_count=1),
                      "_n": grouped.size()}, axis=1).reset_index()
    temp = temp[temp["_n"] > 1].drop(columns="_n")

    keys = temp[DEDUP_DELETE_KEY].dropna().drop_duplicates()
    found = lookup(d, DEDUP_DELETE_KEY, keys, DEDUP_DELETE_KEY, [])
    deleted = int(found["_hit"].sum())
    d = d.loc[~found["_hit"]]

    inserted = temp.assign(**{B_CODE: pd.NA})
    inserted = normalize_types(inserted)
    r.frame = pd.concat([d, inserted], ignore_index=True)
    _record(r, date, label, ("SELECT", 0), ("INSERT", len(temp)), ("DELETE", deleted),
            ("INSERT", len(temp)))


def dedup_fixed(r: DayResult, date, label: str) -> None:
    """
    Merge rows with the same OD key, NULL-safe, summing passengers. A stop
    without an ARS is identified by its name.

    Name and code columns are not part of the key; when merged rows disagree on
    them the values of the row with the most passengers are kept (ties: byte
    order, NULLs last), and the group is counted as a conflict.
    """
    d = r.frame
    descriptive = [B_CODE, B_NAME, A_CODE, A_NAME]
    ordered = d.sort_values([PAX] + descriptive, ascending=[False] + [True] * 4,
                            na_position="last", kind="mergesort").copy()
    ordered["_b_stop"] = ordered[B_ARS].fillna("name:" + ordered[B_NAME])
    ordered["_a_stop"] = ordered[A_ARS].fillna("name:" + ordered[A_NAME])
    ordered["_gid"] = ordered.groupby(OD_KEY, dropna=False, sort=False).ngroup()
    sizes = ordered.groupby("_gid").size()
    merged_groups = int((sizes > 1).sum())
    if merged_groups == 0:
        _record(r, date, label, ("MERGED_GROUPS", 0), ("MERGED_ROWS", 0), ("CONFLICTS", 0))
        return
    multi = ordered[ordered["_gid"].isin(sizes.index[sizes > 1])]
    conflicts = int((multi.groupby("_gid")[descriptive].nunique(dropna=False).max(axis=1) > 1).sum())
    totals = ordered.groupby("_gid")[PAX].sum(min_count=1)
    first = ordered.drop_duplicates("_gid", keep="first").set_index("_gid")
    first[PAX] = totals.reindex(first.index).astype("Int64")
    r.frame = normalize_types(first.reset_index(drop=True))
    _record(r, date, label, ("MERGED_GROUPS", merged_groups),
            ("MERGED_ROWS", int(sizes[sizes > 1].sum())), ("CONFLICTS", conflicts))


def correct_day(day: pd.DataFrame, date, mode: str) -> DayResult:
    if mode not in MODES:
        raise ValueError(f"mode must be one of {MODES}")
    dedup = dedup_faithful if mode == "faithful" else dedup_fixed
    r = DayResult(day.copy(), day.iloc[0:0].copy())
    virtual_stop_steps(r, date)
    dedup(r, date, "dedup.virtual")
    sequence_steps(r, date)
    dedup(r, date, "dedup.sequence")
    same_stop_steps(r, date)
    dedup(r, date, "dedup.samestop")
    return r


# --------------------------------------------------------------------------- #
# Whole file
# --------------------------------------------------------------------------- #

class ConservationError(RuntimeError):
    """Passengers in != passengers out + anomaly."""


def passenger_balance(before: pd.DataFrame, after: pd.DataFrame, anomaly: pd.DataFrame) -> pd.DataFrame:
    def per_route(frame):
        return frame.groupby([DATE, ROUTE], dropna=False)[PAX].sum()
    table = pd.concat({"before": per_route(before), "after": per_route(after),
                       "anomaly": per_route(anomaly)}, axis=1).fillna(0).astype("int64")
    table["diff"] = table["after"] + table["anomaly"] - table["before"]
    return table


def correct(frame: pd.DataFrame, mode: str = "fixed") -> tuple[pd.DataFrame, pd.DataFrame, dict]:
    frame = normalize_types(frame)
    outputs, anomalies, steps = [], [], []
    untouched = frame[frame[DATE].isna()]
    for date, day in frame[frame[DATE].notna()].groupby(DATE, sort=True):
        result = correct_day(day, date, mode)
        outputs.append(result.frame)
        anomalies.append(result.anomaly)
        steps += result.steps
    outputs.append(untouched)
    out = normalize_types(pd.concat(outputs, ignore_index=True)) if outputs else frame.iloc[0:0]
    anomaly = normalize_types(pd.concat(anomalies, ignore_index=True)) if anomalies else frame.iloc[0:0]

    balance = passenger_balance(frame, out, anomaly)
    unbalanced = balance[balance["diff"] != 0]
    report = {
        "mode": mode,
        "rows": {"input": len(frame), "output": len(out), "anomaly": len(anomaly)},
        "passengers": {"input": int(frame[PAX].sum()), "output": int(out[PAX].sum()),
                       "anomaly": int(anomaly[PAX].sum())},
        "unbalanced_routes": len(unbalanced),
        "passenger_diff": int(balance["diff"].sum()),
        "null_boarding_code": {"input": int(frame[B_CODE].isna().sum()),
                               "output": int(out[B_CODE].isna().sum())},
        "steps": steps,
    }
    if mode == "fixed" and len(unbalanced):
        sample = unbalanced.head(10).to_string()
        raise ConservationError(
            f"Passengers not conserved on {len(unbalanced)} routes "
            f"(net {int(balance['diff'].sum()):+,}):\n{sample}")
    return out, anomaly, report


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Java odcorrection workflow in pandas.")
    parser.add_argument("--input", required=True, type=Path,
                        help="od_correction_pipeline.py output (pipe, utf-8-sig)")
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--anomaly", required=True, type=Path)
    parser.add_argument("--mode", choices=MODES, default="fixed")
    parser.add_argument("--report", type=Path, default=None, help="Step counts and totals as JSON")
    args = parser.parse_args(argv)

    frame = read_od(args.input)
    out, anomaly, report = correct(frame, args.mode)
    write_od(out, args.output)
    write_od(anomaly, args.anomaly)
    for step in report["steps"]:
        tags = ", ".join(f"{kind} {count:,}" for kind, count in step["tags"]) or "-"
        print(f"  {step['date']:>8} {step['step']:<30} {tags}")
    p = report["passengers"]
    print(f"mode={args.mode}: rows {report['rows']['input']:,} -> {report['rows']['output']:,} "
          f"(+ anomaly {report['rows']['anomaly']:,}); passengers {p['input']:,} -> {p['output']:,} "
          f"+ {p['anomaly']:,} anomaly; net {report['passenger_diff']:+,} on "
          f"{report['unbalanced_routes']} routes; NULL boarding codes "
          f"{report['null_boarding_code']['input']:,} -> {report['null_boarding_code']['output']:,}")
    if args.report:
        args.report.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
