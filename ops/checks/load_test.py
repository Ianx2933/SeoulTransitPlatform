"""Small load test of the deployed API, before the public release.

Read-only requests from N users at once (default 1, 5, 10, 20), each user
calling the main views one after another with a different route or district
each time, so the answers do not all come from the cache. Per level and view:
requests, errors, median, 95th percentile and slowest response.

It goes to the production database, so it is kept short (--per-user requests
per user, default 6) and stops at the first level where more than 20% of
requests fail. Run it at a quiet hour, never during the Tuesday 05:17 pipeline.
Months and hours are left out (they read BigQuery and cost money per query).

Writes load_test.md to --out. Standard library only.

    python ops/checks/load_test.py --out $HOME/Downloads
"""
from __future__ import annotations

import argparse
import json
import random
import statistics
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

DEFAULT_BASE = "https://seoul-transit-prod.web.app"
TIMEOUT_S = 60
STOP_ERROR_RATE = 0.2


def call(base: str, path: str, params: dict) -> tuple[float, int]:
    url = f"{base}{path}?{urllib.parse.urlencode(params)}"
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT_S) as resp:
            resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        status = e.code
    except Exception:  # timeout, connection reset
        status = 0
    return time.perf_counter() - start, status


def views(dongs: list[str], routes: list[str]):
    """(name, path, params) for one request of each view, with a random target."""
    route = random.choice(routes)
    dong = random.choice(dongs)
    day = random.choice(["weekday", "saturday", "sunday_holiday"])
    return [
        ("route stops", f"/api/curated-od/routes/{urllib.parse.quote(route)}/stops", {"date": day}),
        ("road-load route", "/api/road-load/routes", {"routes": route, "date": day}),
        ("district OD", f"/api/dong-od/{dong}", {"date": day, "direction": "from", "limit": 15}),
        ("district bus routes", f"/api/dong-od/{dong}/routes", {"date": day}),
        ("district road load", f"/api/dong-od/{dong}/route-load", {"date": day, "direction": "both"}),
        ("district how far", f"/api/dong-od/{dong}/reach", {"date": day, "direction": "from"}),
    ]


def run_level(base: str, users: int, per_user: int, dongs: list[str], routes: list[str]) -> dict[str, list[tuple[float, int]]]:
    results: dict[str, list[tuple[float, int]]] = {}
    lock = threading.Lock()

    def user(_: int) -> None:
        for _ in range(per_user):
            name, path, params = random.choice(views(dongs, routes))
            elapsed, status = call(base, path, params)
            with lock:
                results.setdefault(name, []).append((elapsed, status))

    with ThreadPoolExecutor(max_workers=users) as pool:
        list(pool.map(user, range(users)))
    return results


def pct(values: list[float], q: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round(q * (len(ordered) - 1))))]


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--levels", default="1,5,10,20", help="users at once, comma separated")
    ap.add_argument("--per-user", type=int, default=6)
    ap.add_argument("--out", default=".")
    args = ap.parse_args()

    # Warm up (cold start) and get district codes.
    # Firebase Hosting only forwards /api/** to Cloud Run, so warm up on an API path.
    elapsed, status = call(args.base, "/api/map/lines", {"mode": "bus"})
    print(f"warm-up /api/map/lines: {status} in {elapsed:.1f} s")
    with urllib.request.urlopen(f"{args.base}/api/dong-od/summary?date=weekday&level=dong", timeout=TIMEOUT_S) as r:
        rows = json.loads(r.read().decode("utf-8"))
    dongs = [row["code"] for row in rows if str(row.get("code", "")).startswith("11")]
    # Numbered trunk and branch routes from the map's bus line list. A route without OD answers
    # 404; that is counted apart, not as a failure.
    with urllib.request.urlopen(f"{args.base}/api/map/lines?mode=bus", timeout=TIMEOUT_S) as r:
        lines_json = json.loads(r.read().decode("utf-8"))
    routes = [str(x) for x in lines_json if str(x).isdigit() and len(str(x)) in (3, 4)]
    random.seed(20261120)
    print(f"{len(dongs)} districts and {len(routes)} routes to pick from")

    lines = ["# Load test", "", f"{args.base}, {time.strftime('%Y-%m-%d %H:%M')}, "
             f"{args.per_user} requests per user, months and hours left out.", "",
             "Errors leave out 404 (a route without OD); status 0 is a timeout or a dropped connection.", "",
             "| users | view | requests | errors | median s | p95 s | max s |", "|---|---|---|---|---|---|---|"]
    for users in [int(x) for x in args.levels.split(",")]:
        started = time.perf_counter()
        results = run_level(args.base, users, args.per_user, dongs, routes)
        wall = time.perf_counter() - started
        total = sum(len(v) for v in results.values())
        errors = sum(1 for v in results.values() for _, s in v if s not in (200, 404))
        for name in sorted(results):
            times = [t for t, _ in results[name]]
            errs = sum(1 for _, s in results[name] if s not in (200, 404))
            codes = sorted({s for _, s in results[name] if s not in (200, 404)})
            lines.append(f"| {users} | {name} | {len(times)} | {errs}{' ' + str(codes) if codes else ''} | "
                         f"{statistics.median(times):.2f} | {pct(times, 0.95):.2f} | {max(times):.2f} |")
        lines.append(f"| {users} | **all** | {total} | {errors} | | | wall {wall:.1f} s |")
        print(f"{users} users: {total} requests, {errors} errors, {wall:.1f} s")
        if total and errors / total > STOP_ERROR_RATE:
            lines += ["", f"Stopped after {users} users: more than {STOP_ERROR_RATE:.0%} of requests failed."]
            print("stopping: error rate too high")
            break
        time.sleep(5)

    out = Path(args.out) / "load_test.md"
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
