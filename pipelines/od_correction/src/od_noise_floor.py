"""
How accurate can any estimate of one day's OD be? A lower bound from one day.

Even if a model knew each OD pair's true expected count exactly, the count on a
given day would still scatter around it. Treating that scatter as Poisson, the
smallest achievable error at a level of aggregation is

    WAPE floor = sum over keys of E|X - lambda| / sum over keys of lambda

with lambda taken as the observed count. It is a floor, not an estimate of a
model's error: a real model also gets lambda wrong, and pairs that happen to be
zero on the day are not in the file at all. Use it to see at which level
(pair, stop, route) an estimate can be useful before building one.

    python pipelines/od_correction/src/od_noise_floor.py data/od/fixed_20260915.csv
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

import numpy as np
import pandas as pd


def poisson_mad(lam: np.ndarray) -> np.ndarray:
    """E|X - lambda| for X ~ Poisson(lambda): 2 lambda^(k+1) e^-lambda / k!, k = floor(lambda)."""
    lam = np.asarray(lam, dtype=float)
    k = np.floor(lam)
    out = np.zeros_like(lam)
    positive = lam > 0
    l, kk = lam[positive], k[positive]
    log_mad = math.log(2) + (kk + 1) * np.log(l) - l - np.vectorize(math.lgamma)(kk + 1)
    out[positive] = np.exp(log_mad)
    return out


def stop_id(ars: pd.Series, name: pd.Series) -> pd.Series:
    return ars.where(ars.ne(""), "name:" + name)


LEVELS = {
    "OD pair on a route": ["노선명", "_b", "_a"],
    "OD pair, any route": ["_b", "_a"],
    "boarding stop on a route": ["노선명", "_b"],
    "boarding stop, any route": ["_b"],
    "route": ["노선명"],
}


def floors(frame: pd.DataFrame) -> pd.DataFrame:
    d = frame.copy()
    d["_b"] = stop_id(d["승차_정류장ARS"], d["승차_정류장명"])
    d["_a"] = stop_id(d["하차_정류장ARS"], d["하차_정류장명"])
    d["승객수"] = pd.to_numeric(d["승객수"])
    rows = []
    for level, keys in LEVELS.items():
        lam = d.groupby(keys)["승객수"].sum().to_numpy(dtype=float)
        lam = lam[lam > 0]
        rows.append({
            "level": level,
            "keys": len(lam),
            "median passengers per key": float(np.median(lam)),
            "share of passengers in keys <= 3": float(lam[lam <= 3].sum() / lam.sum()),
            "WAPE floor": float(poisson_mad(lam).sum() / lam.sum()),
        })
    return pd.DataFrame(rows)


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(description="Poisson floor on OD estimation error, by level.")
    parser.add_argument("files", nargs="+", type=Path, help="Corrected OD files (pipe, utf-8-sig)")
    args = parser.parse_args(argv)
    pd.set_option("display.width", 160)
    for path in args.files:
        frame = pd.read_csv(path, sep="|", encoding="utf-8-sig", dtype=str, keep_default_na=False)
        table = floors(frame)
        print(f"\n{path.name}: {int(pd.to_numeric(frame['승객수']).sum()):,} passengers")
        print(table.to_string(index=False, formatters={
            "keys": "{:,}".format,
            "median passengers per key": "{:,.0f}".format,
            "share of passengers in keys <= 3": "{:.1%}".format,
            "WAPE floor": "{:.1%}".format,
        }))


if __name__ == "__main__":
    main()
