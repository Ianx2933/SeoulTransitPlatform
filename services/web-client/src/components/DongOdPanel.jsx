import { useEffect, useMemo, useState } from "react";

import { areaCode, fetchDongOd, fetchDongRouteLoad, fetchDongRoutes } from "../api/dongOdApi.js";
import { useStoredNumber } from "../hooks/useStoredNumber.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { busRouteStyle } from "../utils/busRouteType.js";
import { csvFileName } from "../utils/roadCsv.js";
import { busiestRoutes, DEFAULT_MIN_LOAD, styleSegments } from "../utils/routeLoad.js";
import { guName } from "../utils/seoulGu.js";
import CsvButton from "./CsvButton.jsx";
import RouteLoadLegend from "./RouteLoadLegend.jsx";

const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];
const DAY_NAMES = { weekday: "Weekday", saturday: "Saturday", sunday_holiday: "Sunday & holiday" };
const LEVELS = ["dong", "gu", "city"];
const LEVEL_NAMES = { dong: "This district", gu: "Its gu", city: "All of Seoul" };
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
 * Each result is kept with the request it answers, so switching tabs never
 * draws one tab's data with another tab's layout (the routes tab used to
 * crash on flow data).
 */
export default function DongOdPanel({
  code,
  name,
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
  const [minLoad, setMinLoad] = useStoredNumber("roadMinLoad", DEFAULT_MIN_LOAD);
  const [pickedRoutes, setPickedRoutes] = useState([]);
  const [answer, setAnswer] = useState(null);
  const [state, setState] = useState("idle");
  const [error, setError] = useState("");

  useEffect(() => setDayType(initialDayType), [initialDayType]);

  const routesAllowed = level !== "city";
  const activeView = (view === "routes" || view === "road") && !routesAllowed ? "from" : view;
  const area = areaCode(code, level);
  const requestKey = [activeView, level, dest, area, dayType, activeView === "road" ? loadDirection : ""].join("|");

  useEffect(() => {
    if (!code) return undefined;
    let ignore = false;
    setState("loading");
    setError("");
    const request =
      activeView === "routes"
        ? fetchDongRoutes({ code: area, level, dayType })
        : activeView === "road"
          ? fetchDongRouteLoad({ code: area, level, dayType, direction: loadDirection })
          : fetchDongOd({ code: area, level, dest, dayType, direction: activeView, limit: 15 });
    request
      .then((result) => {
        if (ignore) return;
        setAnswer({ key: requestKey, view: activeView, result });
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [requestKey]);

  const current = answer && answer.key === requestKey ? answer : null;
  const roadResult = current && current.view === "road" ? current.result : null;
  const roadRoutes = useMemo(() => (roadResult ? busiestRoutes(roadResult.segments) : []), [roadResult]);
  // Ticks only count for routes this result has; a new area keeps the ones it shares.
  const activeRoutes = pickedRoutes.filter((r) => roadRoutes.some((x) => x.route === r));
  const activeRoutesKey = activeRoutes.join(",");
  const styled = useMemo(
    () => (roadResult ? styleSegments(roadResult.segments, { minLoad, routes: activeRoutes }) : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [roadResult, minLoad, activeRoutesKey]
  );

  // The map draws the road layer only while this view shows its result.
  useEffect(() => {
    onRouteLoad?.(roadResult ? { result: roadResult, minLoad, routes: activeRoutes } : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [roadResult, minLoad, activeRoutesKey, onRouteLoad]);
  useEffect(() => () => onRouteLoad?.(null), [onRouteLoad]);

  const toggleRoute = (route) =>
    setPickedRoutes((prev) => (prev.includes(route) ? prev.filter((r) => r !== route) : [...prev, route]));

  const areaTitle =
    level === "city" ? tx("Seoul") : level === "gu" ? guName(area, lang) || area : name || code;

  const placeName = (row, unit) => {
    if (row.code === "outside") return tx("Outside Seoul");
    if (unit === "gu") return guName(row.code, lang) || row.name || row.code;
    return row.name || row.code;
  };

  const rowUnit = current?.result?.destLevel || dest;

  return (
    <section className="side-card" aria-labelledby="dong-od-title">
      <h2 id="dong-od-title" className="section-title">{tx("District OD: {name}", { name: areaTitle })}</h2>
      <p className="note">{tx("Rail and bus, typical day, from TOPIS district OD.")}</p>

      <div role="group" aria-label={tx("Area")} className="segmented segmented--fill">
        {LEVELS.map((l) => (
          <button key={l} type="button" aria-pressed={level === l} onClick={() => setLevel(l)}>
            {tx(LEVEL_NAMES[l])}
          </button>
        ))}
      </div>
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
      </div>
      {activeView === "road" && (
        <div role="group" aria-label={tx("Riders")} className="segmented segmented--fill">
          {LOAD_DIRECTIONS.map((d) => (
            <button key={d} type="button" aria-pressed={loadDirection === d} onClick={() => setLoadDirection(d)}>
              {tx(LOAD_DIRECTION_NAMES[d])}
            </button>
          ))}
        </div>
      )}
      {activeView !== "routes" && activeView !== "road" && (
        <div role="group" aria-label={tx("Group by")} className="segmented segmented--fill">
          <button type="button" aria-pressed={dest === "dong"} onClick={() => setDest("dong")}>{tx("By district")}</button>
          <button type="button" aria-pressed={dest === "gu"} onClick={() => setDest("gu")}>{tx("By gu")}</button>
        </div>
      )}

      {state === "loading" && <p className="note">{tx("Loading…")}</p>}
      {state === "error" && (
        <p className="field-error">
          {activeView === "road" && level === "gu"
            ? tx("A whole gu took too long or failed. Pick “This district” to see one district at a time.")
            : error}
        </p>
      )}

      {current && current.view !== "routes" && current.view !== "road" && (
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
              const clickable = rowUnit === "dong" && f.code !== "outside" && f.code !== code;
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
                    {f.code === area ? ` (${tx("inside the area")})` : ""}
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
            {tx("{count} routes, {riders} riders boarding here a day", {
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
          <div className="road-controls">
            <label className="field-inline">
              {tx("Hide segments under")}
              <input
                type="number"
                min="0"
                step="1"
                inputMode="numeric"
                value={minLoad}
                onChange={(e) => setMinLoad(Math.max(0, Number(e.target.value) || 0))}
              />
              {tx("riders")}
            </label>
            <label className="check">
              <input type="checkbox" checked={boundariesOn} onChange={(e) => onBoundariesChange?.(e.target.checked)} />
              {tx("Show district boundaries")}
            </label>
          </div>
          <p className="note num">
            {tx("{riders} riders a day; {shown} segments shown, {hidden} under the minimum hidden", {
              riders: fmt(roadResult.passengers),
              shown: styled.shown.toLocaleString(),
              hidden: styled.hidden.toLocaleString()
            })}
          </p>
          <RouteLoadLegend styled={styled} minLoad={minLoad} />
          <CsvButton result={roadResult} fileName={csvFileName("road", areaTitle, roadResult.direction, roadResult.dayType)} />
          {styled.shown === 0 && <p className="note">{tx("No bus riders on the road for this choice.")}</p>}

          {roadRoutes.length > 0 && (
            <fieldset className="route-picks">
              <legend className="flow-title">
                {activeRoutes.length === 0
                  ? tx("Routes ({count}) — tick to show only those", { count: roadRoutes.length })
                  : tx("Showing {picked} of {count} routes", { picked: activeRoutes.length, count: roadRoutes.length })}
              </legend>
              <button
                type="button"
                className="link-button"
                disabled={activeRoutes.length === 0}
                onClick={() => setPickedRoutes([])}
              >
                {tx("Clear all")}
              </button>
              <ul className="route-picks__list">
                {roadRoutes.map((r) => (
                  <li key={r.route}>
                    <label className="check">
                      <input type="checkbox" checked={activeRoutes.includes(r.route)} onChange={() => toggleRoute(r.route)} />
                      <span className="route-chip__dot" style={{ background: busRouteStyle(r.route).bg }} aria-hidden="true" />
                      <span className="label-strong">{r.route}</span>
                    </label>
                    <span className="num note">{tx("busiest {riders}", { riders: fmt(r.load) })}</span>
                  </li>
                ))}
              </ul>
            </fieldset>
          )}

          {roadResult.unplacedPassengers > 0 && (
            <p className="note num">
              {tx("Not drawn: {riders} riders on routes without a stored path ({routes})", {
                riders: fmt(roadResult.unplacedPassengers),
                routes: roadResult.unplacedRoutes.map((u) => u.route).join(", ")
              })}
            </p>
          )}
        </>
      )}
    </section>
  );
}
