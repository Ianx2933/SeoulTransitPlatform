"""Read-only checks against the deployed API, for the public release.

1. Bar chart vs demand colour. For each route, the route-analysis bar chart
   (riders on board after each stop, /api/curated-od/routes/{route}/stops)
   should equal the road-load colour of the segment to the next stop
   (/api/road-load/routes?routes={route}). Both come from the same typical-day
   OD; the bar chart orders stops by TOPIS sequence, the colour places legs on
   the stored route path. Pairs are compared by (from ARS, to ARS), summed
   when a loop passes the same pair twice.
2. Response times of the main endpoints (each called --repeat times; the
   first call may hit a cold Cloud Run instance, so min and median are shown).

Writes quality_checks.md (summary) and quality_bar_vs_colour.csv (every
compared pair) to --out. Standard library only.

    python ops/checks/quality_checks.py --out $HOME/Downloads
"""
from __future__ import annotations

import argparse
import csv
import json
import statistics
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

DEFAULT_BASE = "https://seoul-transit-prod.web.app"
DEFAULT_ROUTES = "143,302,452,470,6411,7016,2312,1711"
DEFAULT_DONG = "11010530"  # 사직동
TIMEOUT_S = 60


def get_json(base: str, path: str, params: dict) -> tuple[object | None, float, int]:
    url = f"{base}{path}?{urllib.parse.urlencode(params)}"
    start = time.perf_counter()
    try:
        with urllib.request.urlopen(url, timeout=TIMEOUT_S) as resp:
            body = resp.read()
            status = resp.status
    except urllib.error.HTTPError as e:
        return None, time.perf_counter() - start, e.code
    elapsed = time.perf_counter() - start
    return json.loads(body.decode("utf-8")), elapsed, status


def pad(ars: str | None) -> str | None:
    if ars is None:
        return None
    s = str(ars).strip()
    return s.zfill(5) if s.isdigit() else s


def bar_pairs(stops: list[dict]) -> dict[tuple[str, str], float]:
    """Riders on board between consecutive stops, keyed by the ARS pair."""
    out: dict[tuple[str, str], float] = {}
    ordered = sorted(stops, key=lambda s: s["seq"])
    for a, b in zip(ordered, ordered[1:]):
        fa, fb = pad(a.get("ars")), pad(b.get("ars"))
        if not fa or not fb:
            continue
        out[(fa, fb)] = out.get((fa, fb), 0.0) + float(a.get("onBoardAfter") or 0)
    return out


def colour_pairs(road: dict, route: str) -> dict[tuple[str, str], float]:
    out: dict[tuple[str, str], float] = {}
    for seg in road.get("segments", []):
        if not seg.get("fromArs") or not seg.get("toArs"):
            continue
        load = (seg.get("byRoute") or {}).get(route, seg.get("load") or 0)
        key = (pad(seg["fromArs"]), pad(seg["toArs"]))
        out[key] = out.get(key, 0.0) + float(load)
    return out


def compare_route(base: str, route: str, day: str, rows: list[dict], timings: dict) -> dict:
    enc = urllib.parse.quote(route, safe="")
    stops, t1, s1 = get_json(base, f"/api/curated-od/routes/{enc}/stops", {"date": day})
    road, t2, s2 = get_json(base, "/api/road-load/routes", {"date": day, "routes": route})
    timings.setdefault("route stops (bar chart)", []).append(t1)
    timings.setdefault("road-load routes (1 route)", []).append(t2)
    if stops is None or road is None:
        return {"route": route, "status": f"stops {s1}, road {s2}"}
    bars = bar_pairs(stops["stops"])
    colours = colour_pairs(road, route)
    both = sorted(set(bars) & set(colours))
    diffs = []
    for key in both:
        diff = colours[key] - bars[key]
        diffs.append(abs(diff))
        rows.append({"route": route, "from_ars": key[0], "to_ars": key[1],
                     "bar": round(bars[key], 1), "colour": round(colours[key], 1), "diff": round(diff, 1)})
    bar_total = sum(bars.values())
    matched = sum(bars[k] for k in both)
    big = sum(1 for k in both if abs(colours[k] - bars[k]) > max(1.0, 0.01 * bars[k]))
    return {
        "route": route,
        "status": "ok",
        "pairs_bar": len(bars),
        "pairs_colour": len(colours),
        "pairs_both": len(both),
        "matched_share": round(100 * matched / bar_total, 1) if bar_total else None,
        "max_abs_diff": round(max(diffs), 1) if diffs else None,
        "pairs_off_by_1pct": big,
        "unplaced": road.get("unplacedPassengers"),
        "unordered": stops.get("unorderedPassengers"),
    }


def time_endpoint(base: str, name: str, path: str, params: dict, repeat: int, timings: dict) -> object | None:
    data = None
    for _ in range(repeat):
        data, elapsed, status = get_json(base, path, params)
        timings.setdefault(name, []).append(elapsed if status == 200 else float("nan"))
    return data


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default=DEFAULT_BASE)
    ap.add_argument("--routes", default=DEFAULT_ROUTES)
    ap.add_argument("--dong", default=DEFAULT_DONG)
    ap.add_argument("--day", default="weekday")
    ap.add_argument("--repeat", type=int, default=3)
    ap.add_argument("--out", default=".")
    args = ap.parse_args()

    out = Path(args.out).expanduser()
    out.mkdir(parents=True, exist_ok=True)
    timings: dict[str, list[float]] = {}
    rows: list[dict] = []

    results = [compare_route(args.base, r.strip(), args.day, rows, timings)
               for r in args.routes.split(",") if r.strip()]

    day = {"date": args.day}
    dong = args.dong
    time_endpoint(args.base, "dong summary (all dongs)", "/api/dong-od/summary", {**day, "level": "dong"}, args.repeat, timings)
    time_endpoint(args.base, "dong OD", f"/api/dong-od/{dong}", day, args.repeat, timings)
    time_endpoint(args.base, "dong bus routes tab", f"/api/dong-od/{dong}/routes", day, args.repeat, timings)
    road = time_endpoint(args.base, "dong road load (both)", f"/api/dong-od/{dong}/route-load",
                         {**day, "direction": "both"}, args.repeat, timings)
    routes = sorted({r for seg in (road or {}).get("segments", []) for r in seg.get("routes", [])})[:20]
    if routes:
        time_endpoint(args.base, f"road-load routes ({len(routes)} routes)", "/api/road-load/routes",
                      {**day, "routes": ",".join(routes)}, args.repeat, timings)
    busiest = next((s for s in (road or {}).get("segments", []) if s.get("fromArs") and s.get("toArs")), None)
    if busiest:
        time_endpoint(args.base, "road-load segment (select link)", "/api/road-load/segment",
                      {**day, "from": busiest["fromArs"], "to": busiest["toArs"]}, args.repeat, timings)
        time_endpoint(args.base, "road-load stop (boarding)", "/api/road-load/stop",
                      {**day, "ars": busiest["fromArs"], "direction": "board"}, args.repeat, timings)

    with open(out / "quality_bar_vs_colour.csv", "w", newline="", encoding="utf-8-sig") as f:
        w = csv.DictWriter(f, fieldnames=["route", "from_ars", "to_ars", "bar", "colour", "diff"])
        w.writeheader()
        w.writerows(rows)

    lines = [f"# Quality checks ({args.day}, {time.strftime('%Y-%m-%d %H:%M')})", "",
             "## Bar chart vs demand colour", "",
             "| route | status | pairs bar / colour / both | riders matched % | max diff | pairs off >1% | unplaced | unordered |",
             "|---|---|---|---|---|---|---|---|"]
    for r in results:
        if r["status"] != "ok":
            lines.append(f"| {r['route']} | {r['status']} | | | | | | |")
            continue
        lines.append(f"| {r['route']} | ok | {r['pairs_bar']} / {r['pairs_colour']} / {r['pairs_both']} | "
                     f"{r['matched_share']} | {r['max_abs_diff']} | {r['pairs_off_by_1pct']} | {r['unplaced']} | {r['unordered']} |")
    lines += ["", "## Response times (seconds)", "", "| endpoint | calls | min | median | max |", "|---|---|---|---|---|"]
    for name, ts in timings.items():
        ok = [t for t in ts if t == t]
        if not ok:
            lines.append(f"| {name} | {len(ts)} | failed | | |")
            continue
        lines.append(f"| {name} | {len(ts)} | {min(ok):.2f} | {statistics.median(ok):.2f} | {max(ok):.2f} |")
    (out / "quality_checks.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
