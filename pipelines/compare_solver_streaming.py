"""Read-only check that the streamed solvers solve the same batches as the per-batch queries.

For the first few route batches, reads the rows both ways -- the previous query per
batch, and the single ordered stream -- solves both, and requires identical results.
Nothing is written. It also prints how long each read took, which is the part the
streaming change is meant to remove.

    python -m pipelines.compare_solver_streaming --batches 3 --route-batch 25

Uses PIPELINE_DB_URL unless --db-url is given.
"""

from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent / "hourly_stop_pattern"))

from pipelines.hourly_od_estimation import solve_dow_hourly_ratio as ratio  # noqa: E402
from pipelines.hourly_od_estimation.common import make_engine  # noqa: E402
import solve_dow_hourly_stop_pattern as stop_pattern  # noqa: E402

SOLVERS = {"ratio": (ratio, 3), "stop_pattern": (stop_pattern, 4)}


def compare(engine, module, min_months: int, batches: int, route_batch: int,
            work_mem: str) -> None:
    routes = module.load_route_list(engine)
    month_counts = module.load_month_counts(engine)

    started = time.perf_counter()
    end = min(batches * route_batch, len(routes))
    chunks = module.stream_hourly_monthly(engine, routes[0], routes[end - 1], 200_000, work_mem)
    stream = module.route_batches(chunks, routes, route_batch, 0, end)
    try:
        mark = time.perf_counter()
        for n, (start, rows) in enumerate(stream):
            if n == batches:
                break
            streamed_s = time.perf_counter() - mark
            new = module.solve_patterns(month_counts, rows, min_months)

            t = time.perf_counter()
            old_rows = module.load_hourly_monthly(engine, routes[start:start + route_batch])
            query_s = time.perf_counter() - t
            old = module.solve_patterns(month_counts, old_rows, min_months)

            pd.testing.assert_frame_equal(new.reset_index(drop=True),
                                          old.reset_index(drop=True), check_exact=True)
            label = f"routes {start + 1}-{min(start + route_batch, len(routes))}"
            first = " (includes sorting the window)" if n == 0 else ""
            print(f"  {label}: {len(rows):,} rows, {len(new):,} solved rows identical; "
                  f"stream {streamed_s:.1f}s{first}, per-batch query {query_s:.1f}s")
            mark = time.perf_counter()
    finally:
        stream.close()
        chunks.close()
    print(f"  total {time.perf_counter() - started:.1f}s")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db-url", default=os.environ.get("PIPELINE_DB_URL"),
                        required="PIPELINE_DB_URL" not in os.environ)
    parser.add_argument("--solver", choices=["ratio", "stop_pattern", "both"], default="both")
    parser.add_argument("--batches", type=int, default=3)
    parser.add_argument("--route-batch", type=int, default=25)
    parser.add_argument("--work-mem", default="256MB")
    args = parser.parse_args()

    engine = make_engine(args.db_url)
    names = list(SOLVERS) if args.solver == "both" else [args.solver]
    for name in names:
        module, min_months = SOLVERS[name]
        print(f"{name}:")
        compare(engine, module, min_months, args.batches, args.route_batch, args.work_mem)
    print("All compared batches identical.")


if __name__ == "__main__":
    main()
