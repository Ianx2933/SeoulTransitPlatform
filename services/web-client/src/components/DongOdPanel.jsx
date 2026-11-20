import { useEffect, useMemo, useRef, useState } from "react";

import { areaCode, fetchDongOd, fetchDongReach, fetchDongRouteLoad, fetchDongRoutes } from "../api/dongOdApi.js";
import { useStoredNumber } from "../hooks/useStoredNumber.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { basisFileTag, basisKey, fixedScaleMax, hoursRange, isPeriodUnavailable, isPlain, TYPICAL } from "../utils/basis.js";
import { csvFileName } from "../utils/roadCsv.js";
import { busiestRoutes, DEFAULT_MIN_LOAD, styleSegments } from "../utils/routeLoad.js";
import { guName } from "../utils/seoulGu.js";
import BasisControls, { PeriodUnavailable } from "./BasisControls.jsx";
import RoadLoadControls from "./RoadLoadControls.jsx";

const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];
const DAY_NAMES = { weekday: "Weekday", saturday: "Saturday", sunday_holiday: "Sunday & holiday" };
const LEVELS = ["dong", "gu", "city"];
const LEVEL_NAMES = { dong: "This district", gu: "Its gu", city: "All of Seoul", group: "Chosen districts" };
const MAX_GROUP = 20;
const REACH_NAMES = {
  same: "Inside the same district",
  "0-1": "Under 1 km",
  "1-3": "1–3 km",
  "3-5": "3–5 km",
  "5-10": "5–10 km",
  "10-20": "10–20 km",
  "20+": "20 km or more"
};
const LOAD_DIRECTIONS = ["od", "do", "both"];
const LOAD_DIRECTION_NAMES = { od: "Boarding here", do: "Alighting here", both: "Both (once each)" };

/**
 * District OD for the chosen district (행정동), its 구 or all of Seoul, by rail
 * and bus: where the riders go, where riders arriving there come from, and
 * the bus routes boarding there with the districts their riders alight in.
 *
 * "On the road" draws the area's bus riders on the map along the roads they
 * ride: onRouteLoad hands { result, minLoad, routes } to the map (null clears
 * it). Ticked routes narrow the lines to those routes; none ticked shows all.
 *
 * Months (BigQuery) apply to every tab; boarding hours only to the bus routes
 * and road tabs, the others being whole days (BasisControls says so).
 *
 * Each result is kept with the request it answers, so switching tabs never
 * draws one tab's data with another tab's layout (the routes tab used to
 * crash on flow data).
 */
export default function DongOdPanel({
  code,
  name,
  picked = [],
  initialDayType = "weekday",
  onPickDistrict,
  onRouteLoad,
  boundariesOn = true,
  onBoundariesChange
}) {
  const { tx, lang } = useI18n();
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const pct = (part, whole) => (whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : "-");
  const [dayType, setDayType] = useState(initialDayType);
  const [level, setLevel] = useState("dong");
  const [dest, setDest] = useState("dong");
  const [view, setView] = useState("from");
  const [loadDirection, setLoadDirection] = useState("both");
  const [reachDirection, setReachDirection] = useState("from");
  const [minLoad, setMinLoad] = useStoredNumber("roadMinLoad", DEFAULT_MIN_LOAD);
  const [pickedRoutes, setPickedRoutes] = useState([]);
  const [basis, setBasis] = useState(TYPICAL);
  const [answer, setAnswer] = useState(null);
  const [state, setState] = useState("idle");
  const [error, setError] = useState("");

  useEffect(() => setDayType(initialDayType), [initialDayType]);

  // Districts picked on the map with Ctrl/Shift-click form a group; picking a
  // second one switches to it, going back to one leaves it.
  const groupCodes = useMemo(() => picked.map((d) => d.districtCode).filter(Boolean).slice(0, MAX_GROUP), [picked]);
  const groupOk = groupCodes.length >= 2;
  const prevGroupOk = useRef(groupOk);
  useEffect(() => {
    if (groupOk && !prevGroupOk.current) setLevel("group");
    if (!groupOk && prevGroupOk.current) setLevel((l) => (l === "group" ? "dong" : l));
    prevGroupOk.current = groupOk;
  }, [groupOk]);
  const levels = groupOk ? [...LEVELS, "group"] : LEVELS;

  const routesAllowed = level !== "city";
  const activeView = (view === "routes" || view === "road") && !routesAllowed ? "from" : view;
  const area = areaCode(code, level, groupCodes);
  const hoursView = activeView === "routes" || activeView === "road";
  const inArea = (unitCode) => (level === "group" ? groupCodes.includes(unitCode) : unitCode === area);
  const requestKey = [
    activeView,
    level,
    dest,
    area,
    dayType,
    activeView === "road" ? loadDirection : activeView === "reach" ? reachDirection : "",
    basisKey(basis, { withHours: hoursView })
  ].join("|");

  // The request runs when its key changes; its parameters are read from a ref.
  const paramsRef = useRef(null);
  paramsRef.current = { activeView, area, level, dest, dayType, loadDirection, reachDirection, basis };

  useEffect(() => {
    const p = paramsRef.current;
    // A group needs two districts; the level switches back a render later.
    if (!code || (p.level === "group" && !p.area.includes(","))) return undefined;
    let ignore = false;
    setState("loading");
    setError("");
    const request =
      p.activeView === "routes"
        ? fetchDongRoutes({ code: p.area, level: p.level, dayType: p.dayType, basis: p.basis })
        : p.activeView === "reach"
          ? fetchDongReach({ code: p.area, level: p.level, dayType: p.dayType, direction: p.reachDirection, basis: p.basis })
        : p.activeView === "road"
          ? fetchDongRouteLoad({
              code: p.area,
              level: p.level,
              dayType: p.dayType,
              direction: p.loadDirection,
              basis: p.basis
            })
          : fetchDongOd({
              code: p.area,
              level: p.level,
              dest: p.dest,
              dayType: p.dayType,
              direction: p.activeView,
              limit: 15,
              basis: p.basis
            });
    request
      .then((result) => {
        if (ignore) return;
        setAnswer({ key: requestKey, view: p.activeView, result });
        setState("done");
      })
      .catch((e) => {
        if (ignore) return;
        setAnswer(null);
        setError(e.message);
        setState("error");
      });
    return () => {
      ignore = true;
    };
  }, [code, requestKey]);

  const current = answer && answer.key === requestKey ? answer : null;
  const roadResult = current && current.view === "road" ? current.result : null;
  const roadRoutes = useMemo(() => (roadResult ? busiestRoutes(roadResult.segments) : []), [roadResult]);
  // Ticks only count for routes this result has; a new area keeps the ones it shares.
  const activeRoutes = useMemo(
    () => pickedRoutes.filter((r) => roadRoutes.some((x) => x.route === r)),
    [pickedRoutes, roadRoutes]
  );
  const fixedMax = roadResult ? fixedScaleMax(roadResult, basis) : null;
  const styled = useMemo(
    () => (roadResult ? styleSegments(roadResult.segments, { minLoad, routes: activeRoutes, fixedMax }) : null),
    [roadResult, minLoad, activeRoutes, fixedMax]
  );

  // The map draws the road layer only while this view shows its result.
  useEffect(() => {
    onRouteLoad?.(roadResult ? { result: roadResult, minLoad, routes: activeRoutes, basis, fixedMax } : null);
  }, [roadResult, minLoad, activeRoutes, basis, fixedMax, onRouteLoad]);
  useEffect(() => () => onRouteLoad?.(null), [onRouteLoad]);

  const areaTitle =
    level === "group"
      ? tx("{count} chosen districts", { count: groupCodes.length })
      : level === "city"
        ? tx("Seoul")
        : level === "gu"
          ? guName(area, lang) || area
          : name || code;

  const placeName = (row, unit) => {
    if (row.code === "outside") return tx("Outside Seoul");
    if (unit === "gu") return guName(row.code, lang) || row.name || row.code;
    return row.name || row.code;
  };

  const rowUnit = current?.result?.destLevel || dest;

  return (
    <section className="side-card" aria-labelledby="dong-od-title">
      <h2 id="dong-od-title" className="section-title">{tx("District OD: {name}", { name: areaTitle })}</h2>
      <p className="note">{tx("Rail and bus from TOPIS district OD; bus routes and the road from route stop OD.")}</p>

      <div role="group" aria-label={tx("Area")} className="segmented segmented--fill">
        {levels.map((l) => (
          <button key={l} type="button" aria-pressed={level === l} onClick={() => setLevel(l)}>
            {tx(LEVEL_NAMES[l])}
          </button>
        ))}
      </div>
      {level === "group" ? (
        <p className="note">{picked.slice(0, MAX_GROUP).map((d) => d.districtName || d.districtCode).join(" · ")}</p>
      ) : (
        <p className="note">{tx("Ctrl- or Shift-click more districts on the map to see them together.")}</p>
      )}
      <div role="group" aria-label={tx("Day type")} className="segmented segmented--fill">
        {DAY_TYPES.map((d) => (
          <button key={d} type="button" aria-pressed={dayType === d} onClick={() => setDayType(d)}>
            {tx(DAY_NAMES[d])}
          </button>
        ))}
      </div>
      <div role="group" aria-label={tx("View")} className="segmented segmented--fill">
        <button type="button" aria-pressed={activeView === "from"} onClick={() => setView("from")}>{tx("Where they go")}</button>
        <button type="button" aria-pressed={activeView === "to"} onClick={() => setView("to")}>{tx("Where they come from")}</button>
        <button type="button" aria-pressed={activeView === "routes"} disabled={!routesAllowed} onClick={() => setView("routes")}>
          {tx("Bus routes")}
        </button>
        <button type="button" aria-pressed={activeView === "road"} disabled={!routesAllowed} onClick={() => setView("road")}>
          {tx("On the road")}
        </button>
        <button type="button" aria-pressed={activeView === "reach"} onClick={() => setView("reach")}>
          {tx("How far")}
        </button>
      </div>
      <BasisControls
        basis={basis}
        onChange={setBasis}
        dayType={dayType}
        withHours={hoursView}
        showFixed={activeView === "road"}
        dayOnlyNote={tx("Whole days in this tab; boarding hours work in Bus routes and On the road.")}
      />
      {activeView === "reach" && (
        <div role="group" aria-label={tx("Riders")} className="segmented segmented--fill">
          <button type="button" aria-pressed={reachDirection === "from"} onClick={() => setReachDirection("from")}>
            {tx("Leaving here")}
          </button>
          <button type="button" aria-pressed={reachDirection === "to"} onClick={() => setReachDirection("to")}>
            {tx("Arriving here")}
          </button>
        </div>
      )}
      {activeView === "road" && (
        <div role="group" aria-label={tx("Riders")} className="segmented segmented--fill">
          {LOAD_DIRECTIONS.map((d) => (
            <button key={d} type="button" aria-pressed={loadDirection === d} onClick={() => setLoadDirection(d)}>
              {tx(LOAD_DIRECTION_NAMES[d])}
            </button>
          ))}
        </div>
      )}
      {activeView !== "routes" && activeView !== "road" && activeView !== "reach" && (
        <div role="group" aria-label={tx("Group by")} className="segmented segmented--fill">
          <button type="button" aria-pressed={dest === "dong"} onClick={() => setDest("dong")}>{tx("By district")}</button>
          <button type="button" aria-pressed={dest === "gu"} onClick={() => setDest("gu")}>{tx("By gu")}</button>
        </div>
      )}

      {state === "loading" && <p className="note">{tx("Loading…")}</p>}
      {state === "error" && isPeriodUnavailable(error) && !isPlain(basis) && (
        <PeriodUnavailable message={error} basis={basis} onTypical={() => setBasis({ ...TYPICAL, fixed: basis.fixed })} />
      )}
      {state === "error" && !(isPeriodUnavailable(error) && !isPlain(basis)) && (
        <p className="field-error">
          {activeView === "road" && level === "gu"
            ? tx("A whole gu took too long or failed. Pick “This district” to see one district at a time.")
            : error}
        </p>
      )}

      {current && current.view === "reach" && <ReachCard result={current.result} fmt={fmt} tx={tx} lang={lang} />}

      {current && current.view !== "routes" && current.view !== "road" && current.view !== "reach" && (
        <>
          <p className="note num">
            {tx("Departures {dep} · arrivals {arr} a day", {
              dep: fmt(current.result.departures),
              arr: fmt(current.result.arrivals)
            })}
          </p>
          <p className="note num">
            {tx("Departures by rail {rail} ({railShare}) · by bus {bus}", {
              rail: fmt(current.result.subwayDepartures),
              railShare: pct(current.result.subwayDepartures, current.result.departures),
              bus: fmt(current.result.busDepartures)
            })}
          </p>
          <ol className="flow-list">
            {current.result.flows.map((f) => {
              const clickable = rowUnit === "dong" && f.code !== "outside" && !inArea(f.code) && f.code !== code;
              return (
                <li key={f.code}>
                  <span>
                    {clickable ? (
                      <button type="button" className="link-button" onClick={() => onPickDistrict?.(f.code)}>
                        {placeName(f, rowUnit)}
                      </button>
                    ) : (
                      placeName(f, rowUnit)
                    )}
                    {inArea(f.code) ? ` (${tx("inside the area")})` : ""}
                  </span>
                  <span className="num">
                    {fmt(f.total)}
                    <span className="note"> {tx("rail {s} · bus {b}", { s: fmt(f.subway), b: fmt(f.bus) })}</span>
                  </span>
                </li>
              );
            })}
          </ol>
        </>
      )}

      {current && current.view === "routes" && (
        <>
          <p className="note num">
            {basis.hours
              ? tx("{count} routes, {riders} riders boarding here {hours}", {
                  count: current.result.routes.length,
                  riders: fmt(current.result.boardings),
                  hours: hoursRange(basis.hours)
                })
              : tx("{count} routes, {riders} riders boarding here a day", {
                  count: current.result.routes.length,
                  riders: fmt(current.result.boardings)
                })}
          </p>
          {current.result.routes.length === 0 ? (
            <p className="note">{tx("No bus riders board here.")}</p>
          ) : (
            <ol className="flow-list">
              {current.result.routes.slice(0, 15).map((r) => (
                <li key={r.route} className="dong-route">
                  <span className="label-strong">{r.route}</span>
                  <span className="num">{fmt(r.boardings)}</span>
                  <span className="note dong-route__dests">
                    {(r.destinations || []).map((d) => `${d.name || d.code} ${fmt(d.passengers)}`).join(" · ")}
                  </span>
                </li>
              ))}
            </ol>
          )}
        </>
      )}
      {roadResult && styled && (
        <>
          <p className="note">
            {roadResult.direction === "od"
              ? tx("Only bus riders who board in this area, on every segment they ride. Routes on the same road are added up.")
              : roadResult.direction === "do"
                ? tx("Only bus riders who get off in this area, on every segment they ride. Routes on the same road are added up.")
                : tx("Only bus riders who board or get off in this area (a trip inside it counts once). Riders just passing through are left out.")}
          </p>
          <RoadLoadControls
            result={roadResult}
            styled={styled}
            minLoad={minLoad}
            onMinLoad={setMinLoad}
            routes={roadRoutes}
            picked={activeRoutes}
            onPicked={setPickedRoutes}
            csvName={csvFileName("road", areaTitle, roadResult.direction, roadResult.dayType, basisFileTag(basis))}
            hours={basis.hours}
            extra={
              <label className="check">
                <input type="checkbox" checked={boundariesOn} onChange={(e) => onBoundariesChange?.(e.target.checked)} />
                {tx("Show district boundaries")}
              </label>
            }
          />
        </>
      )}
    </section>
  );
}

/**
 * Riders by straight-line distance between districts: one bar per band
 * (share of riders with a distance), rail and bus apart, and the medians.
 * Below it, bus riders outside Seoul from route stop OD (busOutside), which
 * district OD cannot measure.
 */
function ReachCard({ result, fmt, tx, lang }) {
  const measured = result.total - result.outside - result.unknown;
  const max = Math.max(...result.bands.map((b) => b.total), 1);
  const pct = (v) => (measured > 0 ? `${((v / measured) * 100).toFixed(1)}%` : "-");
  return (
    <>
      <p className="note num">
        {tx("Half the riders go {km} km or less (rail {rail} km · bus {bus} km), straight line between districts.", {
          km: result.medianKm ?? "-",
          rail: result.subwayMedianKm ?? "-",
          bus: result.busMedianKm ?? "-"
        })}
      </p>
      <ol className="flow-list reach-list">
        {result.bands.map((b) => (
          <li key={b.key}>
            <span>{tx(REACH_NAMES[b.key] || b.key)}</span>
            <span className="num">
              {fmt(b.total)} <span className="note">({pct(b.total)})</span>
            </span>
            <span className="reach-bar" aria-hidden="true">
              <span className="reach-bar__fill" style={{ width: `${(b.total / max) * 100}%` }} />
            </span>
            <span className="note">{tx("rail {s} · bus {b}", { s: fmt(b.subway), b: fmt(b.bus) })}</span>
          </li>
        ))}
      </ol>
      <p className="note num">
        {tx("Outside Seoul {outside} a day, not measured: TOPIS gives one code for everywhere outside Seoul.", {
          outside: fmt(result.outside)
        })}
      </p>
      {result.busOutside && <BusOutsideCard bus={result.busOutside} fmt={fmt} tx={tx} lang={lang} />}
    </>
  );
}

/**
 * Seoul city bus riders between the area and the capital area outside
 * Seoul, counted from route stop OD, which holds Seoul city bus routes only
 * (no Gyeonggi or Incheon buses): a part of the outside figure above, shown
 * as a share of district OD's bus riders outside Seoul (districtOdBus). Bands as in ReachCard, then the cities and counties.
 */
function BusOutsideCard({ bus, fmt, tx, lang }) {
  if (!(bus.total > 0) && !(bus.unlocated > 0)) return null;
  const max = Math.max(...bus.bands.map((b) => b.total), 1);
  const pct = (v) => (bus.total > 0 ? `${((v / bus.total) * 100).toFixed(1)}%` : "-");
  const areaName = (a) => (lang === "ko" ? a.name : a.nameEn) || a.name || a.code;
  return (
    <>
      <h3 className="label-strong">{tx("Outside Seoul by Seoul city bus")}</h3>
      <p className="note">
        {bus.districtOdBus > 0
          ? tx("Seoul city buses only, from route stop OD (Gyeonggi and Incheon buses are not in it): {part} of the {all} bus riders outside Seoul in district OD ({share}).", {
              part: fmt(bus.total),
              all: fmt(bus.districtOdBus),
              share: `${((bus.total / bus.districtOdBus) * 100).toFixed(1)}%`
            })
          : tx("Seoul city buses only, from route stop OD: Gyeonggi and Incheon buses are not in it, so this is less than the outside figure above.")}
      </p>
      <p className="note num">
        {tx("{total} riders a day, half within {km} km (straight line between districts).", {
          total: fmt(bus.total),
          km: bus.medianKm ?? "-"
        })}
      </p>
      {bus.total > 0 && (
        <ol className="flow-list reach-list">
          {bus.bands.map((b) => (
            <li key={b.key}>
              <span>{tx(REACH_NAMES[b.key] || b.key)}</span>
              <span className="num">
                {fmt(b.total)} <span className="note">({pct(b.total)})</span>
              </span>
              <span className="reach-bar" aria-hidden="true">
                <span className="reach-bar__fill" style={{ width: `${(b.total / max) * 100}%` }} />
              </span>
            </li>
          ))}
        </ol>
      )}
      {bus.areas.length > 0 && (
        <>
          <p className="note">{tx("Cities and counties with the most riders")}</p>
          <ol className="flow-list">
            {bus.areas.map((a) => (
              <li key={a.code}>
                <span>{areaName(a)}</span>
                <span className="num">
                  {fmt(a.passengers)} <span className="note">({pct(a.passengers)})</span>
                </span>
              </li>
            ))}
          </ol>
          <p className="note">
            {tx("Gyeonggi's general gu are grouped into their city (Suji, Giheung, Cheoin → Yongin); Incheon gu names are those before the July 2026 reorganisation.")}
          </p>
        </>
      )}
      {bus.unlocated > 0 && (
        <p className="note num">
          {tx("Stop at the other end not located: {riders} a day (may be in Seoul).", { riders: fmt(bus.unlocated) })}
        </p>
      )}
    </>
  );
}

