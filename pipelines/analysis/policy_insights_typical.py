"""Recompute the numbers in docs/analysis/policy_insights.md from the typical-day OD.

The policy write-up predates the platform and does not say which day its numbers
come from. This reads the typical-day OD in BigQuery (transit_marts.
od_typical_route_stop_serving: the mean of every corrected TOPIS OD day of the day
type over the last twelve months) and rebuilds the three analyses:

  1. Route 143: in-vehicle load along the route, highest segments first
  2. Routes 302 / 303: alighting passengers per stop between sequences 10 and 21
     (Sangdaewon depot to Seongho Market)
  3. Routes 302 / 303 / 4425 / 452: share of the route's passengers riding within
     a core segment (boarding and alighting both inside it), both directions
  4. The same routes: demand that crosses the segment's far end (Taepyeong or
     Bokjeong station, or the Seoul-bound buffer stop ARS 48028), per direction and
     per stop, with start points where more than 25 / 50 / 100 crossing riders a day
     are on board (cumulative over the stops, --thresholds), and how far
     past the end the crossing riders go (stops with at least --reach a day)
  5. The same routes: for every stop in the span, riders on board after it over the
     whole route, how far more than 25 / 50 / 100 / 200 of them stay on board
     (--reach-levels), how many pass each segment end, and where they alight

Stop names are matched with LIKE patterns passed as query parameters, so Korean
text never goes through the PowerShell command line. Output is Markdown.

    python pipelines/analysis/policy_insights_typical.py --out policy_typical.md
    python pipelines/analysis/policy_insights_typical.py --day-type saturday
"""
from __future__ import annotations

import argparse
import sys

PROJECT = "project-9c77d932-f0d2-47bb-947"
TABLE = f"{PROJECT}.transit_marts.od_typical_route_stop_serving"
MAX_BYTES = 2 * 1024 ** 3

# Route 452 stop names (2026-11): Sanseong station's stop is 한국폴리텍대학.
# 성남문화예술교육센터; Bokjeong station is 452's first stop, so a segment towards
# Bokjeong ends at 복정동행정복지센터 instead.
BOKJEONG_TERMINAL_452 = "%복정역환승센터%"  # 452 has no Songpa depot stop in OD data; seq 1 is 복정역환승센터
SANSEONG = "%한국폴리텍대학%|%산성역%"
BOKJEONG_452 = "%복정동행정복지센터%"
LIST_STOPS = ["452"]   # printed first, with ARS, to check names against

# (route, label, stop A pattern, stop B pattern). The segment runs from the first
# stop matching A to the first matching B (outbound) and from the last matching B
# to the last matching A (inbound), by stop sequence; a trip counts when it boards
# and alights inside one of them. When A or B occurs only once on the route
# (the route passes it in one direction only), there is no inbound segment. Statistics Korea (통계청) is now 국가데이터처, so
# its stop is 국가데이터처.태평역.
CORE_PAIRS = [
    ("303", "상대원 ↔ 잠실역", "%상대원%", "%잠실역%"),
    ("303", "상대원 ↔ 통계청·태평역", "%상대원%", "%국가데이터처%|%통계청%|%태평역%"),
    ("302", "상대원 ↔ 잠실역", "%상대원%", "%잠실역%"),
    ("302", "상대원 ↔ 통계청·태평역", "%상대원%", "%국가데이터처%|%통계청%|%태평역%"),
    ("4425", "상대원 ↔ 복정역", "%상대원%", "%복정%"),
    ("4425", "은곡마을 ↔ 삼성역", "%은곡마을%", "%삼성역%"),
    ("452", "복정역환승센터 ↔ 단대오거리", BOKJEONG_TERMINAL_452, "%단대오거리%"),
    ("452", "산성역 ↔ 태평역", SANSEONG, "%국가데이터처%|%통계청%|%태평역%"),
    ("452", "산성역 ↔ 복정동행정복지센터", SANSEONG, BOKJEONG_452),
]

# Section 4: (route, label, A pattern, B pattern). Crossing demand is counted at B,
# the far end of the segment seen from A.
TAEPYEONG = "%국가데이터처%|%통계청%|%태평역%"
# Intermediate buffer: ARS 48028 on the Seoul-bound side only, so only the
# outbound (Seoul-bound) leg is cut there; ARS 48030 (terminus-bound) is not a cut.
BUFFER = "ars:48028"
CROSSING_SEGMENTS = [
    *[(r, f"상대원 → {name}", "%상대원%", pat)
      for r in ("302", "303", "4425")
      for name, pat in (("태평역", TAEPYEONG), ("버퍼 48028", BUFFER), ("복정역", "%복정%"))],
    ("452", "복정역환승센터 → 단대오거리", BOKJEONG_TERMINAL_452, "%단대오거리%"),
    ("452", "산성역(한국폴리텍대학) → 태평역", SANSEONG, TAEPYEONG),
    ("452", "산성역(한국폴리텍대학) → 버퍼 48028", SANSEONG, BUFFER),
    ("452", "산성역(한국폴리텍대학) → 복정동행정복지센터", SANSEONG, BOKJEONG_452),
]
# Section 5: per stop, where its riders go. One span per route wide enough to hold
# every segment above; the cuts are the B ends of that route's segments.
OD_SPANS = [
    ("302", "상대원 → 복정역", "%상대원%", "%복정%"),
    ("303", "상대원 → 복정역", "%상대원%", "%복정%"),
    ("4425", "상대원 → 복정역", "%상대원%", "%복정%"),
    ("452", "복정역환승센터 → 복정동행정복지센터", BOKJEONG_TERMINAL_452, BOKJEONG_452),
]


def like_any(column: str, param: str, key: str = "stop_key") -> str:
    """
    SQL that is true when a stop matches any of the '|'-separated patterns in
    @param: a LIKE pattern on its name, or ars:NNNNN for its exact ARS number.
    """
    return (f"EXISTS (SELECT 1 FROM UNNEST(SPLIT(@{param}, '|')) p "
            f"WHERE IF(STARTS_WITH(p, 'ars:'), {key} = SUBSTR(p, 5), {column} LIKE p))")


def run(client, bq, sql: str, params: list) -> list[dict]:
    cfg = bq.QueryJobConfig(query_parameters=params, maximum_bytes_billed=MAX_BYTES, use_legacy_sql=False)
    return [dict(r.items()) for r in client.query(sql, job_config=cfg).result()]


def table(rows: list[dict], columns: list[tuple[str, str, str]]) -> str:
    """columns: (key, header, format)."""
    head = "| " + " | ".join(h for _, h, _ in columns) + " |"
    sep = "|" + "|".join("---:" if f else "---" for _, _, f in columns) + "|"
    body = ["| " + " | ".join(format(r[k], f) if f and r[k] is not None else str(r[k])
                              for k, _, f in columns) + " |" for r in rows]
    return "\n".join([head, sep, *body])


def route_load(client, bq, day_type: str, route: str, top: int) -> tuple[list[dict], dict]:
    sql = f"""
    WITH t AS (SELECT * FROM `{TABLE}` WHERE day_type = @day_type AND route_name = @route),
    flows AS (
      SELECT board_seq AS seq, ANY_VALUE(board_name) AS name, SUM(passengers) AS ons, 0.0 AS offs
      FROM t GROUP BY board_seq
      UNION ALL
      SELECT alight_seq, ANY_VALUE(alight_name), 0.0, SUM(passengers) FROM t GROUP BY alight_seq
    ),
    stops AS (
      SELECT seq, ANY_VALUE(name) AS name, SUM(ons) AS ons, SUM(offs) AS offs
      FROM flows WHERE seq IS NOT NULL GROUP BY seq
    ),
    load AS (
      SELECT seq, name, ons, offs,
             SUM(ons - offs) OVER (ORDER BY seq ROWS UNBOUNDED PRECEDING) AS onboard,
             LEAD(name) OVER (ORDER BY seq) AS next_name
      FROM stops
    )
    SELECT seq, name, next_name, ROUND(ons, 1) AS ons, ROUND(offs, 1) AS offs, ROUND(onboard, 1) AS onboard,
           (SELECT ROUND(SUM(passengers), 1) FROM t) AS route_total
    FROM load ORDER BY onboard DESC LIMIT {int(top)}
    """
    rows = run(client, bq, sql, [bq.ScalarQueryParameter("day_type", "STRING", day_type),
                                 bq.ScalarQueryParameter("route", "STRING", route)])
    return rows, {"route_total": rows[0]["route_total"] if rows else None}


def alighting_by_stop(client, bq, day_type: str, route: str, first: int, last: int) -> list[dict]:
    sql = f"""
    SELECT alight_seq AS seq, ANY_VALUE(alight_name) AS name, ROUND(SUM(passengers), 1) AS alighting
    FROM `{TABLE}`
    WHERE day_type = @day_type AND route_name = @route AND alight_seq BETWEEN @first AND @last
    GROUP BY alight_seq ORDER BY alighting DESC
    """
    return run(client, bq, sql, [bq.ScalarQueryParameter("day_type", "STRING", day_type),
                                 bq.ScalarQueryParameter("route", "STRING", route),
                                 bq.ScalarQueryParameter("first", "INT64", first),
                                 bq.ScalarQueryParameter("last", "INT64", last)])


def core_pair_share(client, bq, day_type: str, route: str, a: str, b: str) -> dict:
    sql = f"""
    WITH t AS (SELECT * FROM `{TABLE}` WHERE day_type = @day_type AND route_name = @route),
    stops AS (
      SELECT board_seq AS seq, board_name AS name, board_key AS stop_key FROM t
      UNION DISTINCT SELECT alight_seq, alight_name, alight_key FROM t
    ),
    ends AS (
      SELECT
        MIN(IF({like_any('name', 'a')}, seq, NULL)) AS a_first,
        MAX(IF({like_any('name', 'a')}, seq, NULL)) AS a_last,
        MIN(IF({like_any('name', 'b')}, seq, NULL)) AS b_first,
        MAX(IF({like_any('name', 'b')}, seq, NULL)) AS b_last,
        COUNT(DISTINCT IF({like_any('name', 'a')}, name, NULL)) AS a_stops,
        COUNT(DISTINCT IF({like_any('name', 'b')}, name, NULL)) AS b_stops
      FROM stops
    )
    SELECT
      ROUND(SUM(IF((t.board_seq BETWEEN LEAST(a_first, b_first) AND GREATEST(a_first, b_first)
                    AND t.alight_seq BETWEEN LEAST(a_first, b_first) AND GREATEST(a_first, b_first))
                OR (b_last > b_first AND a_last > a_first
                    AND t.board_seq BETWEEN LEAST(b_last, a_last) AND GREATEST(b_last, a_last)
                    AND t.alight_seq BETWEEN LEAST(b_last, a_last) AND GREATEST(b_last, a_last)),
                passengers, 0)), 1) AS pair,
      ROUND(SUM(passengers), 1) AS route_total,
      ANY_VALUE(IF(b_last > b_first AND a_last > a_first,
                   FORMAT('%d-%d / %d-%d', LEAST(a_first, b_first), GREATEST(a_first, b_first),
                                           LEAST(b_last, a_last), GREATEST(b_last, a_last)),
                   FORMAT('%d-%d / none', LEAST(a_first, b_first), GREATEST(a_first, b_first)))) AS seq_ranges,
      ANY_VALUE(a_stops) AS a_stops,
      ANY_VALUE(b_stops) AS b_stops
    FROM t CROSS JOIN ends
    """
    row = run(client, bq, sql, [bq.ScalarQueryParameter("day_type", "STRING", day_type),
                                bq.ScalarQueryParameter("route", "STRING", route),
                                bq.ScalarQueryParameter("a", "STRING", a),
                                bq.ScalarQueryParameter("b", "STRING", b)])[0]
    row["share"] = round(100 * row["pair"] / row["route_total"], 1) if row["route_total"] else None
    return row


def segment_ends(client, bq, day_type: str, route: str, a: str, b: str) -> dict:
    """First and last stop sequence matching A and B on the route."""
    sql = f"""
    WITH t AS (SELECT * FROM `{TABLE}` WHERE day_type = @day_type AND route_name = @route),
    stops AS (
      SELECT board_seq AS seq, board_name AS name, board_key AS stop_key FROM t
      UNION DISTINCT SELECT alight_seq, alight_name, alight_key FROM t
    )
    SELECT
      MIN(IF({like_any('name', 'a')}, seq, NULL)) AS a_first,
      MAX(IF({like_any('name', 'a')}, seq, NULL)) AS a_last,
      MIN(IF({like_any('name', 'b')}, seq, NULL)) AS b_first,
      MAX(IF({like_any('name', 'b')}, seq, NULL)) AS b_last,
      (SELECT ROUND(SUM(passengers), 1) FROM t) AS route_total
    FROM stops
    """
    return run(client, bq, sql, [bq.ScalarQueryParameter("day_type", "STRING", day_type),
                                 bq.ScalarQueryParameter("route", "STRING", route),
                                 bq.ScalarQueryParameter("a", "STRING", a),
                                 bq.ScalarQueryParameter("b", "STRING", b)])[0]


def legs(ends: dict) -> list[tuple[str, int, int]]:
    """
    (direction, a_seq, b_seq) for each pass of the route over the segment: the
    first occurrences (outbound) and, when both stops occur twice, the last ones
    (inbound). a_seq < b_seq means the bus runs A towards B on that leg.
    """
    if None in (ends["a_first"], ends["b_first"]):
        return []
    out = [("outbound", ends["a_first"], ends["b_first"])]
    if ends["a_last"] > ends["a_first"] and ends["b_last"] > ends["b_first"]:
        out.append(("inbound", ends["a_last"], ends["b_last"]))
    return out


def crossing(client, bq, day_type: str, route: str, a_seq: int, b_seq: int,
             thresholds: list[float], reach: float) -> dict:
    """
    Demand inside the segment and demand crossing its B end on one leg.
    A towards B (a_seq < b_seq): riders boarding inside who alight past B, per
    boarding stop, and where past B they alight.
    B towards A (b_seq < a_seq): riders boarding before B who alight inside, per
    alighting stop, and where before B they boarded.

    For each threshold two start points: the stop farthest from B that reaches it
    (an isolated stop counts), and how far from B it holds without a break (the
    terminus would otherwise always win on the way back, where everyone alights).
    """
    lo, hi = min(a_seq, b_seq), max(a_seq, b_seq)
    toward_b = a_seq < b_seq
    if toward_b:
        cross = "t.board_seq BETWEEN @lo AND @hi AND t.alight_seq > @hi"
        stop_seq, stop_name = "t.board_seq", "t.board_name"
        far_seq, far_name = "t.alight_seq", "t.alight_name"
    else:
        cross = "t.board_seq < @lo AND t.alight_seq BETWEEN @lo AND @hi"
        stop_seq, stop_name = "t.alight_seq", "t.alight_name"
        far_seq, far_name = "t.board_seq", "t.board_name"
    params = [bq.ScalarQueryParameter("day_type", "STRING", day_type),
              bq.ScalarQueryParameter("route", "STRING", route),
              bq.ScalarQueryParameter("lo", "INT64", lo),
              bq.ScalarQueryParameter("hi", "INT64", hi)]
    base = f"FROM `{TABLE}` t WHERE t.day_type = @day_type AND t.route_name = @route"
    summary = run(client, bq, f"""
        SELECT
          ROUND(SUM(IF(t.board_seq BETWEEN @lo AND @hi AND t.alight_seq BETWEEN @lo AND @hi, passengers, 0)), 1) AS inside,
          ROUND(SUM(IF({cross}, passengers, 0)), 1) AS crossing
        {base}""", params)[0]
    per_stop = run(client, bq, f"""
        SELECT {stop_seq} AS seq, ANY_VALUE({stop_name}) AS name, ROUND(SUM(passengers), 1) AS crossing
        {base} AND {cross}
        GROUP BY seq ORDER BY seq""", params)
    beyond = run(client, bq, f"""
        SELECT {far_seq} AS seq, ANY_VALUE({far_name}) AS name, ROUND(SUM(passengers), 1) AS riders
        {base} AND {cross}
        GROUP BY seq ORDER BY seq""", params)

    # Crossing riders on board after each stop: toward B they accumulate as they
    # board (sum of boardings up to the stop); from past B they thin out as they
    # alight (sum of alightings after the stop).
    if toward_b:
        acc = 0.0
        for r in per_stop:
            acc += r["crossing"]
            r["on_board"] = round(acc, 1)
    else:
        acc = 0.0
        for r in reversed(per_stop):
            r["on_board"] = round(acc, 1)
            acc += r["crossing"]
    starts = {}
    for th in thresholds:
        over = [r for r in per_stop if r["on_board"] > th]
        # toward B: the first stop from A after which more than th crossing riders are on board;
        # from past B: the last stop (farthest from B) after which more than th are still on board.
        starts[th] = (over[0] if toward_b else over[-1]) if over else None
    for r in per_stop:
        met = [th for th in thresholds if r["on_board"] > th]
        r["mark"] = f">{max(met):g}" if met else ""
    reached = [r for r in beyond if r["riders"] >= reach]
    farthest = (reached[-1] if toward_b else reached[0]) if reached else None
    return dict(lo=lo, hi=hi, toward_b=toward_b, per_stop=per_stop, starts=starts,
                reached=reached, farthest=farthest, **summary)


def stop_load(client, bq, day_type: str, route: str, lo: int, hi: int,
              cuts: list[int], toward_b: bool, top: int, thresholds: list[float]) -> list[dict]:
    """
    For every stop k in lo..hi, the riders on board after it over the whole route
    (boarded at or before k, alighting after k), then followed forward: for each
    threshold, the farthest stop j after which more than that many of them are still
    on board (alight after j), i.e. how far the stop's load carries. Also how many
    pass each cut (toward B: alight after it; from past B: boarded before it) and
    their most common alighting stops.
    """
    cut_cols = "".join(
        f", ROUND(SUM(IF({'alight_seq > ' + str(c) if toward_b else 'board_seq < ' + str(c)}, passengers, 0)), 1) AS cut_{n}"
        for n, c in enumerate(cuts))
    reach_cols = "".join(
        f", MAX(IF(remain > {float(th)}, j, NULL)) AS reach_{n}" for n, th in enumerate(thresholds))
    sql = f"""
    WITH t AS (SELECT * FROM `{TABLE}` WHERE day_type = @day_type AND route_name = @route),
    names AS (
      SELECT seq, ANY_VALUE(name) AS name FROM (
        SELECT board_seq AS seq, board_name AS name FROM t
        UNION ALL SELECT alight_seq, alight_name FROM t) GROUP BY seq
    ),
    k AS (SELECT seq FROM UNNEST(GENERATE_ARRAY(@lo, @hi)) seq),
    onb AS (SELECT k.seq, t.board_seq, t.alight_seq, t.alight_name, t.passengers
            FROM k JOIN t ON t.board_seq <= k.seq AND t.alight_seq > k.seq),
    load AS (SELECT seq, ROUND(SUM(passengers), 1) AS on_board{cut_cols} FROM onb GROUP BY seq),
    js AS (SELECT DISTINCT alight_seq AS j FROM t),
    rem AS (SELECT onb.seq, js.j, SUM(onb.passengers) AS remain
            FROM onb JOIN js ON js.j >= onb.seq AND onb.alight_seq > js.j
            GROUP BY onb.seq, js.j),
    reach AS (SELECT seq{reach_cols} FROM rem GROUP BY seq),
    dest AS (SELECT seq, alight_seq, SUM(passengers) AS r FROM onb GROUP BY seq, alight_seq),
    topd AS (SELECT dest.seq, STRING_AGG(FORMAT('%d %s %.1f', dest.alight_seq, n.name, dest.r), '; '
                                         ORDER BY dest.r DESC LIMIT {int(top)}) AS top_dest
             FROM dest JOIN names n ON n.seq = dest.alight_seq GROUP BY dest.seq)
    SELECT load.*, names.name, topd.top_dest, reach.* EXCEPT (seq) FROM load
    LEFT JOIN names USING (seq) LEFT JOIN topd USING (seq) LEFT JOIN reach USING (seq) ORDER BY seq
    """
    params = [bq.ScalarQueryParameter("day_type", "STRING", day_type),
              bq.ScalarQueryParameter("route", "STRING", route),
              bq.ScalarQueryParameter("lo", "INT64", lo),
              bq.ScalarQueryParameter("hi", "INT64", hi)]
    rows = run(client, bq, sql, params)
    names = {r["seq"]: r["name"] for r in run(client, bq, f"""
        SELECT seq, ANY_VALUE(name) AS name FROM (
          SELECT board_seq AS seq, board_name AS name FROM `{TABLE}` WHERE day_type = @day_type AND route_name = @route
          UNION ALL SELECT alight_seq, alight_name FROM `{TABLE}` WHERE day_type = @day_type AND route_name = @route)
        GROUP BY seq""", params[:2])}
    for r in rows:
        for n in range(len(thresholds)):
            j = r.get(f"reach_{n}")
            r[f"reach_{n}"] = f"{j} {names.get(j, '')}" if j is not None else ""
    return rows


def list_stops(client, bq, day_type: str, route: str) -> list[dict]:
    sql = f"""
    WITH t AS (SELECT * FROM `{TABLE}` WHERE day_type = @day_type AND route_name = @route)
    SELECT seq, ANY_VALUE(stop_key) AS ars, ANY_VALUE(name) AS name,
           ROUND(SUM(ons), 1) AS ons, ROUND(SUM(offs), 1) AS offs FROM (
      SELECT board_seq AS seq, board_key AS stop_key, board_name AS name, passengers AS ons, 0.0 AS offs FROM t
      UNION ALL SELECT alight_seq, alight_key, alight_name, 0.0, passengers FROM t
    ) GROUP BY seq ORDER BY seq
    """
    return run(client, bq, sql, [bq.ScalarQueryParameter("day_type", "STRING", day_type),
                                 bq.ScalarQueryParameter("route", "STRING", route)])


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--day-type", default="weekday", choices=["weekday", "saturday", "sunday_holiday"])
    p.add_argument("--out", help="write Markdown here (UTF-8) instead of printing")
    p.add_argument("--list-stops", metavar="ROUTE",
                   help="only print the route's stops (sequence, name, ons, offs) to check name patterns")
    p.add_argument("--thresholds", default="25,50,100",
                   help="riders on board a day; levels are 'more than' (default 25,50,100)")
    p.add_argument("--reach-levels", default="25,50,100,200",
                   help="section 5: riders still on board ('more than') to follow each stop's load to (default 25,50,100,200)")
    p.add_argument("--top", type=int, default=3,
                   help="most common alighting stops listed per stop in section 5 (default 5)")
    p.add_argument("--reach", type=float, default=15.0,
                   help="riders a day at a stop past the segment end that still count as reached (default 15)")
    args = p.parse_args(argv)

    from google.cloud import bigquery as bq
    client = bq.Client(project=PROJECT)
    d = args.day_type
    thresholds = sorted(float(x) for x in args.thresholds.split(",") if x.strip())
    reach_levels = sorted(float(x) for x in args.reach_levels.split(",") if x.strip())
    if args.list_stops:
        rows = list_stops(client, bq, d, args.list_stops)
        text = (f"# Route {args.list_stops} stops, typical {d}\n\n" +
                table(rows, [("seq", "Seq", "d"), ("ars", "ARS", ""), ("name", "Stop", ""),
                             ("ons", "On", ",.1f"), ("offs", "Off", ",.1f")]))
        if args.out:
            with open(args.out, "w", encoding="utf-8") as f:
                f.write(text)
            print(f"wrote {args.out}")
        else:
            sys.stdout.reconfigure(encoding="utf-8")
            print(text)
        return 0
    out = [f"# Policy insight numbers from the typical {d} OD",
           "",
           f"Source: `{TABLE}`, day_type = {d}: mean daily passengers over the days of that type "
           "in the last twelve months. Passengers are per day, not per hour.",
           ""]

    for route in LIST_STOPS:
        out += [f"## Stops of route {route} (check the names and ARS used below)", "",
                table(list_stops(client, bq, d, route),
                      [("seq", "Seq", "d"), ("ars", "ARS", ""), ("name", "Stop", ""),
                       ("ons", "On", ",.1f"), ("offs", "Off", ",.1f")]), ""]

    rows, meta = route_load(client, bq, d, "143", 10)
    out += ["## 1. Route 143: highest in-vehicle load (cumulative ons minus offs, by stop sequence)",
            "", f"Route total: {meta['route_total']:,} passengers a day.", "",
            table(rows, [("seq", "Seq", ""), ("name", "Stop", ""), ("next_name", "Next stop", ""),
                         ("ons", "On", ",.1f"), ("offs", "Off", ",.1f"), ("onboard", "On board after stop", ",.1f")]),
            ""]

    for route in ("302", "303"):
        rows = alighting_by_stop(client, bq, d, route, 10, 21)
        out += [f"## 2. Route {route}: alighting by stop, sequences 10-21", "",
                table(rows, [("name", "Stop", ""), ("seq", "Seq", "d"), ("alighting", "Alighting", ",.1f")]), ""]

    shares = []
    for route, label, a, b in CORE_PAIRS:
        r = core_pair_share(client, bq, d, route, a, b)
        shares.append(dict(route=route, label=label, **r))
    out += ["## 3. Core segment share of route total (trips inside the segment, both directions)", "",
            table(shares, [("route", "Route", ""), ("label", "Segment", ""), ("seq_ranges", "Seq out / in", ""),
                           ("pair", "Passengers", ",.1f"),
                           ("route_total", "Route total", ",.1f"), ("share", "Share %", ".1f"),
                           ("a_stops", "A stops matched", "d"), ("b_stops", "B stops matched", "d")]),
            "", "A or B stops matched = 0 means a stop name pattern found nothing; check the name.", ""]

    th_text = "/".join(f"{t:g}" for t in thresholds)
    out += ["## 4. Demand crossing the segment end", "",
            "Toward B: riders who board inside the segment and alight past B, by boarding stop. "
            "From past B: riders who boarded before B and alight inside, by alighting stop. "
            "Crossing here: crossing riders who board (toward B) or alight (from past B) at the stop. "
            "Crossing on board: crossing riders on the bus after the stop, accumulated over the stops "
            f"(boardings summed from A toward B; from past B, those not yet alighted). For each level "
            f"(more than {th_text} a day) the stop from which (toward B) or up to which (from past B) "
            "more than that many crossing riders are on board. Reach: where on the other side of B the crossing "
            f"riders get off (toward B) or got on (from past B), stops with at least {args.reach:g} a day; "
            "the farthest of them is the maximum.", ""]
    for route, label, a, b in CROSSING_SEGMENTS:
        ends = segment_ends(client, bq, d, route, a, b)
        out += [f"### Route {route}: {label}", ""]
        found = legs(ends)
        if not found:
            out += ["Stop pattern not found on this route.", ""]
            continue
        for direction, a_seq, b_seq in found:
            c = crossing(client, bq, d, route, a_seq, b_seq, thresholds, args.reach)
            where = "toward B" if c["toward_b"] else "from past B"
            total = ends["route_total"] or 0

            def stop(r, key="crossing"):
                return f"{r['name']} (seq {r['seq']}, {r[key]:,.1f})" if r else "none"
            out += [f"**{direction}, seq {c['lo']}-{c['hi']}, {where}**: within {c['inside']:,.1f} "
                    f"({100 * c['inside'] / total:.1f}% of route), crossing {c['crossing']:,.1f} "
                    f"({100 * c['crossing'] / total:.1f}%).", ""]
            out += [f"- >{th:g} crossing riders on board: " +
                    (f"from {stop(r, 'on_board')} on" if c["toward_b"] else f"up to {stop(r, 'on_board')}")
                    for th, r in c["starts"].items()]
            out += [f"- Reach (≥{args.reach:g}) on the other side of B, farthest: {stop(c['farthest'], 'riders')}", ""]
            if c["per_stop"]:
                out += [table(c["per_stop"], [("seq", "Seq", "d"), ("name", "Stop", ""),
                                              ("crossing", "Crossing here", ",.1f"),
                                              ("on_board", "Crossing on board", ",.1f"), ("mark", "", "")]), ""]
            if c["reached"]:
                side = "Alighting past B" if c["toward_b"] else "Boarding before B"
                out += [f"{side}, stops with ≥{args.reach:g}:", "",
                        table(c["reached"], [("seq", "Seq", "d"), ("name", "Stop", ""), ("riders", "Riders", ",.1f")]), ""]

    out += ["## 5. How far each stop's load carries", "",
            "On board: riders on the bus after the stop over the whole route (all boardings up to the stop "
            "minus all alightings up to it). Follow them forward: '>N until' is the farthest stop after which "
            "more than N of them are still on board, so between the stop and that one more than N of the "
            "stop's riders have yet to alight. Past <cut>: of those on board, how many go past that segment "
            "end (toward B: alight after it; from past B: boarded before it); the buffer is a cut on the "
            "Seoul-bound leg only. Top: their most common alighting stops (seq, name, riders).", ""]
    for route, label, a, b in OD_SPANS:
        ends = segment_ends(client, bq, d, route, a, b)
        out += [f"### Route {route}: {label}", ""]
        found = legs(ends)
        if not found:
            out += ["Stop pattern not found on this route.", ""]
            continue
        cuts = []   # (name, outbound seq, inbound seq or None)
        for r2, lab2, a2, b2 in CROSSING_SEGMENTS:
            if r2 != route:
                continue
            e2 = segment_ends(client, bq, d, route, a2, b2)
            if e2["b_first"] is None:
                continue
            inbound = e2["b_last"] if e2["b_last"] > e2["b_first"] else None
            cuts.append((lab2.split("→")[-1].strip(), e2["b_first"], inbound))
        for direction, a_seq, b_seq in found:
            toward_b = a_seq < b_seq
            lo, hi = min(a_seq, b_seq), max(a_seq, b_seq)
            leg_cuts = [(n, co if direction == "outbound" else ci) for n, co, ci in cuts]
            leg_cuts = [(n, c) for n, c in leg_cuts if c is not None]
            rows = stop_load(client, bq, d, route, lo, hi, [c for _, c in leg_cuts], toward_b,
                             args.top, reach_levels)
            cols = [("seq", "Seq", "d"), ("name", "Stop", ""), ("on_board", "On board", ",.1f")]
            cols += [(f"reach_{n}", f">{th:g} until", "") for n, th in enumerate(reach_levels)]
            for n, (name, c) in enumerate(leg_cuts):
                for r in rows:
                    # a cut only applies where it still lies ahead (toward B) or behind (from past B)
                    if (toward_b and r["seq"] > c) or (not toward_b and r["seq"] < c):
                        r[f"cut_{n}"] = ""
                cols.append((f"cut_{n}", f"Past {name} (seq {c})", ""))
            for r in rows:
                for n in range(len(leg_cuts)):
                    if r[f"cut_{n}"] != "":
                        r[f"cut_{n}"] = f"{r[f'cut_{n}']:,.1f}"
                r["top_dest"] = r["top_dest"] or ""
            cols.append(("top_dest", "Top alighting stops", ""))
            out += [f"**{direction}, seq {lo}-{hi}, {'toward B' if toward_b else 'from past B'}**", "",
                    table(rows, cols), ""]

    text = "\n".join(out)
    if args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"wrote {args.out}")
    else:
        sys.stdout.reconfigure(encoding="utf-8")
        print(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
