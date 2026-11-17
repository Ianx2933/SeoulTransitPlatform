import { useEffect, useState } from "react";

import { areaCode, fetchDongOd, fetchDongRoutes } from "../api/dongOdApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { guName } from "../utils/seoulGu.js";

const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];
const DAY_NAMES = { weekday: "Weekday", saturday: "Saturday", sunday_holiday: "Sunday & holiday" };
const LEVELS = ["dong", "gu", "city"];
const LEVEL_NAMES = { dong: "This district", gu: "Its gu", city: "All of Seoul" };

/**
 * District OD for the chosen district (행정동), its 구 or all of Seoul, by rail
 * and bus: where the riders go, where riders arriving there come from, and
 * the bus routes boarding there with the districts their riders alight in.
 *
 * Each result is kept with the request it answers, so switching tabs never
 * draws one tab's data with another tab's layout (the routes tab used to
 * crash on flow data).
 */
export default function DongOdPanel({ code, name, initialDayType = "weekday", onPickDistrict }) {
  const { tx, lang } = useI18n();
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const pct = (part, whole) => (whole > 0 ? `${((part / whole) * 100).toFixed(1)}%` : "-");
  const [dayType, setDayType] = useState(initialDayType);
  const [level, setLevel] = useState("dong");
  const [dest, setDest] = useState("dong");
  const [view, setView] = useState("from");
  const [answer, setAnswer] = useState(null);
  const [state, setState] = useState("idle");
  const [error, setError] = useState("");

  useEffect(() => setDayType(initialDayType), [initialDayType]);

  const routesAllowed = level !== "city";
  const activeView = view === "routes" && !routesAllowed ? "from" : view;
  const area = areaCode(code, level);
  const requestKey = [activeView, level, dest, area, dayType].join("|");

  useEffect(() => {
    if (!code) return undefined;
    let ignore = false;
    setState("loading");
    setError("");
    const request =
      activeView === "routes"
        ? fetchDongRoutes({ code: area, level, dayType })
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
      </div>
      {activeView !== "routes" && (
        <div role="group" aria-label={tx("Group by")} className="segmented segmented--fill">
          <button type="button" aria-pressed={dest === "dong"} onClick={() => setDest("dong")}>{tx("By district")}</button>
          <button type="button" aria-pressed={dest === "gu"} onClick={() => setDest("gu")}>{tx("By gu")}</button>
        </div>
      )}

      {state === "loading" && <p className="note">{tx("Loading…")}</p>}
      {state === "error" && <p className="field-error">{error}</p>}

      {current && current.view !== "routes" && (
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
    </section>
  );
}
