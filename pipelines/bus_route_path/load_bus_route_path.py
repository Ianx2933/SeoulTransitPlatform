"""
Load Seoul bus route geometry, cut into stop-to-stop segments.

For every route in od_typical_route_stop the job asks the Seoul bus route API
(ws.bus.go.kr busRouteInfo, data.go.kr key) for:

    getBusRouteList   route name -> busRouteId (exact busRouteNm match)
    getStaionByRoute  stations in order: seq, arsId, position, transYn
    getRoutePath      the road path as ordered points

Each station is matched to a path vertex with a monotonic assignment (indices
never go backwards, total distance minimal), and the path between consecutive
stations becomes one segment. Segments are keyed by (from_ars, to_ars), so the
same pair of stops served by several routes can be summed later.

The development key allows about 1,000 calls a day and each route costs three,
so a run stops at --max-calls and the next run continues: routes that were
never fetched go first, then the oldest. A quota answer from the gateway ends
the run cleanly as well. Run it daily until every route is fresh.

A route whose station order changed since the last fetch, or a stored route
the API no longer has, is not simply replaced: the old rows are copied to the
bus_route_path_version tables first, so a past period can still be drawn on
the road it ran then. A fetch that fails (API error) leaves the stored path
as it was instead of replacing it with an error row (a route never stored
still gets its error row).

    python -m pipelines.bus_route_path.load_bus_route_path --max-calls 900
    python -m pipelines.bus_route_path.load_bus_route_path --only-route 302 --dry-run

Needs PIPELINE_DB_URL (SQLAlchemy URL) and SEOUL_BUS_API_KEY (decoding form).
The key is never printed.
"""

from __future__ import annotations

import argparse
import math
import os
import re
import sys
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass, field

import requests

BASE_URL = "http://ws.bus.go.kr/api/rest/busRouteInfo/"
CALLS_PER_ROUTE = 3
DEFAULT_MAX_CALLS = 900
DEFAULT_STALE_DAYS = 14
# Consecutive API errors that end a run: an unknown quota or key answer would
# otherwise mark every remaining route as an error.
MAX_CONSECUTIVE_ERRORS = 3
VARIANT_SUFFIX = r"(?:[A-Z]|출근|퇴근)"
EARTH_RADIUS_M = 6_371_008.8

# TOPIS routes that the bus route API keeps as several routes under other
# names. Every variant is stored, each as its own route row whose od_route_nm
# is the TOPIS name; a segment load for the TOPIS route uses the variant that
# serves both stops. The ids were looked up with getBusRouteList (2026-11-17).
ALIASES: dict[str, tuple[tuple[str, str], ...]] = {
    # TOPIS name: ((busRouteId, busRouteNm), ...)
    "한강버스01": (("123000023", "한강버스(서부)"),
                 ("123000024", "한강버스(동부옥수)"),
                 ("123000021", "한강버스(동부압구정)")),
}


class QuotaExceeded(RuntimeError):
    """The gateway refused the call because the daily quota is used up."""


class ApiError(RuntimeError):
    """The API answered with an error code other than 'no result'."""


# --------------------------------------------------------------------------- #
# API
# --------------------------------------------------------------------------- #

def parse_items(body: bytes) -> list[dict[str, str]]:
    """
    itemList elements of a ServiceResult as dicts. headerCd 4 (no result)
    gives an empty list. A data.go.kr gateway error (OpenAPI_ServiceResponse)
    with reason code 22 is a used-up quota.
    """
    if b"<!DOCTYPE" in body[:2048].upper():
        raise ApiError("refusing a document with a DOCTYPE")
    root = ET.fromstring(body)
    if root.tag == "OpenAPI_ServiceResponse":
        code = (root.findtext(".//returnReasonCode") or "").strip()
        msg = (root.findtext(".//returnAuthMsg") or "").strip()
        if code == "22" or "LIMITED" in msg.upper():
            raise QuotaExceeded(msg or "quota exceeded")
        raise ApiError(f"gateway error {code}: {msg}")
    header = (root.findtext(".//headerCd") or "").strip()
    if header == "4":
        return []
    if header != "0":
        raise ApiError(f"headerCd {header}: {(root.findtext('.//headerMsg') or '').strip()}")
    return [{child.tag: (child.text or "").strip() for child in item}
            for item in root.iter("itemList")]


class BusRouteApi:
    def __init__(self, key: str, session: requests.Session | None = None,
                 max_calls: int = DEFAULT_MAX_CALLS, pause: float = 0.1):
        if not key:
            raise SystemExit("SEOUL_BUS_API_KEY is not set")
        self._key = key
        self._session = session or requests.Session()
        self.max_calls = max_calls
        self.calls = 0
        self._pause = pause

    def remaining(self) -> int:
        return self.max_calls - self.calls

    def _call(self, operation: str, param: str, value: str) -> list[dict[str, str]]:
        if self.calls >= self.max_calls:
            raise QuotaExceeded(f"--max-calls {self.max_calls} reached")
        self.calls += 1
        response = self._session.get(BASE_URL + operation,
                                     params={"ServiceKey": self._key, param: value}, timeout=15)
        if response.status_code in (401, 403):
            raise ApiError(f"HTTP {response.status_code} (check the key type)")
        if response.status_code == 429:
            raise QuotaExceeded("HTTP 429")
        response.raise_for_status()
        if self._pause:
            time.sleep(self._pause)
        return parse_items(response.content)

    def route_list(self, name: str):
        return self._call("getBusRouteList", "strSrch", name)

    def stations(self, route_id: str):
        return self._call("getStaionByRoute", "busRouteId", route_id)

    def path(self, route_id: str):
        return self._call("getRoutePath", "busRouteId", route_id)


# --------------------------------------------------------------------------- #
# Geometry
# --------------------------------------------------------------------------- #

def distance_m(lng1: float, lat1: float, lng2: float, lat2: float) -> float:
    """Equirectangular distance; exact enough at city scale."""
    x = math.radians(lng2 - lng1) * math.cos(math.radians((lat1 + lat2) / 2))
    y = math.radians(lat2 - lat1)
    return EARTH_RADIUS_M * math.hypot(x, y)


def match_stations(stations: list[tuple[float, float]], path: list[tuple[float, float]]) -> list[int]:
    """
    Path vertex index for each station, non-decreasing, minimising the summed
    station-to-vertex distance (dynamic programme with a running minimum).
    A plain nearest-vertex search breaks on routes that come back along the
    same road; the order constraint keeps the outbound and return legs apart.
    """
    n, m = len(stations), len(path)
    if n == 0:
        return []
    if m == 0:
        raise ValueError("empty path")
    choice: list[list[int]] = []
    prev = [0.0] * m
    for i, (slng, slat) in enumerate(stations):
        cost = [distance_m(slng, slat, plng, plat) for plng, plat in path]
        best_idx: list[int] = [0] * m
        if i == 0:
            cur = cost
            best_idx = list(range(m))
        else:
            cur = [0.0] * m
            run_min, run_arg = math.inf, 0
            for j in range(m):
                if prev[j] < run_min:
                    run_min, run_arg = prev[j], j
                cur[j] = cost[j] + run_min
                best_idx[j] = run_arg
        choice.append(best_idx)
        prev = cur
    j = min(range(m), key=lambda k: prev[k])
    out = [0] * n
    for i in range(n - 1, -1, -1):
        out[i] = j
        if i > 0:
            j = choice[i][j]
    return out


def line_length(points: list[tuple[float, float]]) -> float:
    return sum(distance_m(*points[k], *points[k + 1]) for k in range(len(points) - 1))


def wkt_linestring(points: list[tuple[float, float]]) -> str:
    return "LINESTRING(" + ", ".join(f"{lng:.7f} {lat:.7f}" for lng, lat in points) + ")"


def pad_ars(value: str | None) -> str | None:
    value = (value or "").strip()
    if not value.isdigit() or int(value) == 0:
        return None
    return value.zfill(5)


# --------------------------------------------------------------------------- #
# One route
# --------------------------------------------------------------------------- #

@dataclass
class Station:
    seq: int
    ars: str | None
    station_id: str
    name: str
    lng: float
    lat: float
    trans_yn: bool
    path_idx: int = 0


@dataclass
class Segment:
    seq: int
    from_ars: str | None
    to_ars: str | None
    points: list[tuple[float, float]]

    @property
    def length_m(self) -> float:
        return line_length(self.points)


@dataclass
class RouteResult:
    route_nm: str
    status: str
    route_id: str | None = None
    route_type: int | None = None
    start_name: str | None = None
    end_name: str | None = None
    n_points: int = 0
    message: str | None = None
    stations: list[Station] = field(default_factory=list)
    segments: list[Segment] = field(default_factory=list)
    od_route_nm: str | None = None   # TOPIS name; route_nm unless an alias variant

    def __post_init__(self):
        if self.od_route_nm is None:
            self.od_route_nm = self.route_nm

    @property
    def turnaround_seq(self) -> int | None:
        return next((s.seq for s in self.stations if s.trans_yn), None)

    @property
    def path_sig(self) -> str | None:
        return station_sig([(s.ars, s.station_id) for s in self.stations]) if self.stations else None


def station_sig(stations: list[tuple[str | None, str | None]]) -> str:
    """Station order as text, ARS or "id:<station id>" joined by ">" (same as STORED_SQL)."""
    return ">".join(ars if ars else f"id:{sid or ''}" for ars, sid in stations)


def _float(value: str | None) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return number if math.isfinite(number) and number != 0 else None


def build_route(route_nm: str, route_row: dict, station_rows: list[dict],
                path_rows: list[dict]) -> RouteResult:
    """Stations, matched path indices and segments for one route (pure)."""
    path_rows = sorted(path_rows, key=lambda r: int(r.get("no") or 0))
    path = [(x, y) for x, y in ((_float(r.get("gpsX")), _float(r.get("gpsY"))) for r in path_rows)
            if x is not None and y is not None]
    stations: list[Station] = []
    for r in sorted(station_rows, key=lambda r: int(r.get("seq") or 0)):
        lng, lat = _float(r.get("gpsX")), _float(r.get("gpsY"))
        if lng is None or lat is None:
            continue
        stations.append(Station(int(r["seq"]), pad_ars(r.get("arsId")), r.get("station", ""),
                                r.get("stationNm", ""), lng, lat,
                                (r.get("transYn") or "").upper() == "Y"))
    result = RouteResult(route_nm, "ok", route_row.get("busRouteId"),
                         int(route_row["routeType"]) if (route_row.get("routeType") or "").isdigit() else None,
                         route_row.get("stStationNm"), route_row.get("edStationNm"), len(path))
    if len(stations) < 2 or len(path) < 2:
        result.status = "error"
        result.message = f"{len(stations)} stations, {len(path)} path points"
        return result
    for s, idx in zip(stations, match_stations([(s.lng, s.lat) for s in stations], path)):
        s.path_idx = idx
    for a, b in zip(stations, stations[1:]):
        points = path[a.path_idx:b.path_idx + 1]
        if len(points) < 2:  # both stations on one vertex: straight line between them
            points = [(a.lng, a.lat), (b.lng, b.lat)]
        result.segments.append(Segment(a.seq, a.ars, b.ars, points))
    result.stations = stations
    return result


def variant_key(od_route_nm: str, api_name: str) -> str:
    """
    Stored name of a variant, "<TOPIS name>|<API name>". The API name alone can
    clash with a TOPIS route of that name (TOPIS lists 2312A as well as 2312),
    and both are kept: each TOPIS route reads only its own rows.
    """
    return f"{od_route_nm}|{api_name}"


def calls_needed(route_nm: str) -> int:
    variants = ALIASES.get(route_nm)
    return 2 * len(variants) if variants else CALLS_PER_ROUTE


def fetch_route(api: BusRouteApi, route_nm: str) -> list[RouteResult]:
    """The stored rows for one TOPIS route: one, or one per alias variant."""
    variants = ALIASES.get(route_nm)
    if variants:
        out = []
        for route_id, name in variants:
            row = {"busRouteId": route_id, "busRouteNm": name}
            r = build_route(variant_key(route_nm, name), row, api.stations(route_id), api.path(route_id))
            r.od_route_nm = route_nm
            out.append(r)
        return out
    listed = api.route_list(route_nm)
    rows = [r for r in listed if r.get("busRouteNm") == route_nm]
    if rows:
        route_row = rows[0]
        return [build_route(route_nm, route_row, api.stations(route_row["busRouteId"]),
                            api.path(route_row["busRouteId"]))]
    # The API may split one TOPIS route into variants: lettered (2312 -> 2312A,
    # 2312B) or commuter runs (8762 -> 8762출근, 8762퇴근).
    pattern = re.compile(re.escape(route_nm) + VARIANT_SUFFIX)
    variants = []
    seen: set[str] = set()
    for r in listed:  # the list can repeat a name; keep its first entry
        name = r.get("busRouteNm") or ""
        if pattern.fullmatch(name) and name not in seen:
            seen.add(name)
            variants.append(r)
    if not variants:
        return [RouteResult(route_nm, "not_found", message="no exact busRouteNm match")]
    out = []
    for row in variants:
        r = build_route(variant_key(route_nm, row["busRouteNm"]), row,
                        api.stations(row["busRouteId"]), api.path(row["busRouteId"]))
        r.od_route_nm = route_nm
        out.append(r)
    return out


# --------------------------------------------------------------------------- #
# Database
# --------------------------------------------------------------------------- #

PLAN_SQL = """
    WITH wanted AS (
        SELECT DISTINCT 노선명 AS route_nm FROM od_typical_route_stop
    ),
    have AS (
        SELECT od_route_nm, MIN(fetched_at) AS fetched_at, bool_or(status = 'error') AS has_error
        FROM bus_route_path_route
        GROUP BY od_route_nm
    )
    SELECT w.route_nm
    FROM wanted w
    LEFT JOIN have h ON h.od_route_nm = w.route_nm
    WHERE h.od_route_nm IS NULL
       OR h.fetched_at < now() - make_interval(days => :stale_days)
       OR (h.has_error AND h.fetched_at < now() - interval '1 day')
    ORDER BY h.fetched_at NULLS FIRST, w.route_nm
"""


def plan_routes(engine, stale_days: int) -> list[str]:
    from sqlalchemy import text
    with engine.connect() as conn:
        return [row[0] for row in conn.execute(text(PLAN_SQL), {"stale_days": stale_days})]


# Stored ok rows of one TOPIS route with their station order, computed from the
# stations (path_sig is empty for rows stored before it existed).
STORED_SQL = """
    SELECT r.route_nm, r.path_since,
           (SELECT string_agg(COALESCE(s.ars, 'id:' || COALESCE(s.station_id, '')), '>' ORDER BY s.seq)
              FROM bus_route_path_station s WHERE s.route_nm = r.route_nm) AS sig
    FROM bus_route_path_route r
    WHERE (r.od_route_nm = :n OR r.route_nm = :n) AND r.status = 'ok'
"""

ARCHIVE_SQL = """
    WITH v AS (
        INSERT INTO bus_route_path_version (route_nm, od_route_nm, route_id, start_name, end_name,
            n_stations, turnaround_seq, path_sig, valid_from, valid_to, reason)
        SELECT route_nm, od_route_nm, route_id, start_name, end_name, n_stations, turnaround_seq,
               :sig, COALESCE(path_since, fetched_at), now(), :reason
        FROM bus_route_path_route WHERE route_nm = :route_nm
        RETURNING version_id
    ), st AS (
        INSERT INTO bus_route_path_version_station (version_id, seq, ars, station_id, station_nm, lng, lat, trans_yn)
        SELECT v.version_id, s.seq, s.ars, s.station_id, s.station_nm, s.lng, s.lat, s.trans_yn
        FROM v, bus_route_path_station s WHERE s.route_nm = :route_nm
    )
    INSERT INTO bus_route_path_version_segment (version_id, seq, from_ars, to_ars, length_m, geom)
    SELECT v.version_id, g.seq, g.from_ars, g.to_ars, g.length_m, g.geom
    FROM v, bus_route_path_segment g WHERE g.route_nm = :route_nm
"""


@dataclass
class SavePlan:
    keep_stored: bool                       # every fetch failed: leave the stored rows alone
    archive: list[tuple[str, str]]          # (stored route_nm, reason changed | gone)
    since: dict[str, object]                # new route_nm -> path_since to carry over


def plan_save(stored: dict[str, tuple[str | None, object]], results: list[RouteResult]) -> SavePlan:
    """
    What to do with the stored ok rows of one TOPIS route (pure).
    stored: route_nm -> (station order, path_since).
    A route kept with the same station order carries its path_since over; one
    whose order changed, or that the fetch no longer returns, is archived. A
    not_found answer archives everything (the route is gone); a fetch with any
    error changes nothing, and the oldest-first plan tries the route again.
    """
    if stored and any(r.status == "error" for r in results):
        return SavePlan(True, [], {})
    new = {r.route_nm: r for r in results if r.status == "ok"}
    archive: list[tuple[str, str]] = []
    since: dict[str, object] = {}
    for name, (sig, path_since) in stored.items():
        r = new.get(name)
        if r is None:
            archive.append((name, "gone"))
        elif sig and r.path_sig != sig:
            archive.append((name, "changed"))
        else:
            since[name] = path_since
    return SavePlan(False, archive, since)


def save_routes(engine, od_route_nm: str, results: list[RouteResult], attempts: int = 3, log=print) -> None:
    """
    Replace every stored row of one TOPIS route in one transaction, archiving
    rows whose station order changed first (plan_save). A dropped connection
    (proxy or server closing it during a long run) rolls the transaction back,
    so it is simply tried again on a fresh connection.
    """
    from sqlalchemy import text
    from sqlalchemy.exc import OperationalError
    for attempt in range(1, attempts + 1):
        try:
            with engine.begin() as conn:
                stored = {row.route_nm: (row.sig, row.path_since)
                          for row in conn.execute(text(STORED_SQL), {"n": od_route_nm})}
                plan = plan_save(stored, results)
                if plan.keep_stored:
                    log(f"  {od_route_nm}: fetch failed, stored path kept")
                    return
                for name, reason in plan.archive:
                    conn.execute(text(ARCHIVE_SQL), {"route_nm": name, "reason": reason,
                                                     "sig": stored[name][0]})
                    log(f"  {name}: earlier path archived ({reason})")
                conn.execute(text("DELETE FROM bus_route_path_route WHERE od_route_nm = :n OR route_nm = :n"),
                             {"n": od_route_nm})
                for r in results:
                    _insert_route(conn, r, plan.since.get(r.route_nm))
            return
        except OperationalError:
            if attempt == attempts:
                raise
            engine.dispose()
            time.sleep(5 * attempt)


def _insert_route(conn, r: RouteResult, path_since=None) -> None:
    from sqlalchemy import text
    conn.execute(text("""
        INSERT INTO bus_route_path_route (route_nm, od_route_nm, route_id, route_type, start_name,
            end_name, n_stations, n_points, turnaround_seq, status, message, fetched_at,
            path_since, path_sig)
        VALUES (:route_nm, :od_route_nm, :route_id, :route_type, :start_name,
            :end_name, :n_stations, :n_points, :turnaround_seq, :status, :message, now(),
            CASE WHEN :status = 'ok' THEN COALESCE(CAST(:path_since AS timestamptz), now()) END, :path_sig)
    """), {"route_nm": r.route_nm, "od_route_nm": r.od_route_nm, "route_id": r.route_id,
           "route_type": r.route_type,
           "start_name": r.start_name, "end_name": r.end_name, "n_stations": len(r.stations),
           "n_points": r.n_points, "turnaround_seq": r.turnaround_seq,
           "status": r.status, "message": r.message,
           "path_since": path_since, "path_sig": r.path_sig})
    if r.stations:
        conn.execute(text("""
            INSERT INTO bus_route_path_station (route_nm, seq, ars, station_id, station_nm,
                lng, lat, trans_yn, path_idx)
            VALUES (:route_nm, :seq, :ars, :station_id, :station_nm, :lng, :lat, :trans_yn, :path_idx)
        """), [{"route_nm": r.route_nm, "seq": s.seq, "ars": s.ars, "station_id": s.station_id,
                "station_nm": s.name, "lng": s.lng, "lat": s.lat, "trans_yn": s.trans_yn,
                "path_idx": s.path_idx} for s in r.stations])
    if r.segments:
        conn.execute(text("""
            INSERT INTO bus_route_path_segment (route_nm, seq, from_ars, to_ars, length_m, geom)
            VALUES (:route_nm, :seq, :from_ars, :to_ars, :length_m, ST_GeomFromText(:wkt, 4326))
        """), [{"route_nm": r.route_nm, "seq": g.seq, "from_ars": g.from_ars, "to_ars": g.to_ars,
                "length_m": g.length_m, "wkt": wkt_linestring(g.points)} for g in r.segments])


# --------------------------------------------------------------------------- #
# Run
# --------------------------------------------------------------------------- #

def run(api: BusRouteApi, routes: list[str], save, log=print) -> dict:
    counts = {"ok": 0, "not_found": 0, "error": 0, "left": 0}
    stopped = None
    errors_in_row = 0
    for i, name in enumerate(routes):
        if api.remaining() < calls_needed(name):
            stopped = "call budget"
            counts["left"] = len(routes) - i
            break
        try:
            results = fetch_route(api, name)
        except QuotaExceeded as e:
            stopped = f"quota: {e}"
            counts["left"] = len(routes) - i
            break
        except (ApiError, requests.RequestException) as e:
            errors_in_row += 1
            log(f"  {name}: API error {str(e)[:200]}")
            if errors_in_row >= MAX_CONSECUTIVE_ERRORS:
                stopped = f"{errors_in_row} API errors in a row"
                counts["left"] = len(routes) - i
                break
            results = [RouteResult(name, "error", message=str(e)[:300])]
        except (ValueError, KeyError) as e:
            results = [RouteResult(name, "error", message=str(e)[:300])]
        else:
            errors_in_row = 0
        save(name, results)
        status = "ok" if all(r.status == "ok" for r in results) else next(
            r.status for r in results if r.status != "ok")
        counts[status] += 1
        for r in results:
            if r.status != "ok":
                log(f"  {r.route_nm}: {r.status} {r.message or ''}")
    log(f"routes ok {counts['ok']}, not found {counts['not_found']}, error {counts['error']}, "
        f"left {counts['left']}, calls {api.calls}" + (f" (stopped: {stopped})" if stopped else ""))
    return counts


def main(argv: list[str] | None = None) -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--db-url", default=os.environ.get("PIPELINE_DB_URL"))
    p.add_argument("--max-calls", type=int, default=DEFAULT_MAX_CALLS)
    p.add_argument("--stale-days", type=int, default=DEFAULT_STALE_DAYS)
    p.add_argument("--only-route", help="fetch one route regardless of age")
    p.add_argument("--dry-run", action="store_true", help="fetch and report, write nothing")
    args = p.parse_args(argv)

    api = BusRouteApi(os.environ.get("SEOUL_BUS_API_KEY", ""), max_calls=args.max_calls)
    engine = None
    if not args.dry_run or not args.only_route:
        if not args.db_url:
            raise SystemExit("PIPELINE_DB_URL or --db-url is required")
        from sqlalchemy import create_engine
        engine = create_engine(args.db_url, pool_pre_ping=True)

    routes = [args.only_route] if args.only_route else plan_routes(engine, args.stale_days)
    print(f"{len(routes)} routes to fetch, budget {args.max_calls} calls")

    def save(od_route_nm: str, results: list[RouteResult]) -> None:
        if args.dry_run:
            for r in results:
                print(f"  {r.route_nm}: {r.status}, {len(r.stations)} stations, "
                      f"{len(r.segments)} segments, {r.n_points} points, turnaround seq {r.turnaround_seq}")
        else:
            save_routes(engine, od_route_nm, results)

    counts = run(api, routes, save)
    if counts["error"] and not counts["ok"]:
        sys.exit(1)


if __name__ == "__main__":
    main()
