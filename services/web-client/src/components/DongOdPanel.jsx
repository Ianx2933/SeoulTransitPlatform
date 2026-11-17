import { useEffect, useState } from "react";

import { fetchDongOd, fetchDongRoutes } from "../api/dongOdApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";

const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];
const DAY_NAMES = { weekday: "Weekday", saturday: "Saturday", sunday_holiday: "Sunday & holiday" };

/**
 * District OD for one chosen district (행정동), all modes: where its riders
 * go, where riders arriving there come from, and the bus routes boarding
 * there with the districts their riders alight in.
 */
export default function DongOdPanel({ code, name, initialDayType = "weekday", onPickDistrict }) {
  const { tx, lang } = useI18n();
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const [dayType, setDayType] = useState(initialDayType);
  const [view, setView] = useState("from");
  const [data, setData] = useState(null);
  const [state, setState] = useState("idle");
  const [error, setError] = useState("");

  useEffect(() => setDayType(initialDayType), [initialDayType]);

  useEffect(() => {
    if (!code) return undefined;
    let ignore = false;
    setState("loading");
    setError("");
    const request =
      view === "routes"
        ? fetchDongRoutes({ code, dayType })
        : fetchDongOd({ code, dayType, direction: view, limit: 15 });
    request
      .then((result) => {
        if (ignore) return;
        setData(result);
        setState("done");
      })
      .catch((e) => {
        if (ignore) return;
        setData(null);
        setError(e.message);
        setState("error");
      });
    return () => {
      ignore = true;
    };
  }, [code, dayType, view]);

  const placeName = (row) => (row.code === "outside" ? tx("Outside Seoul") : row.name || row.code);

  return (
    <section className="side-card" aria-labelledby="dong-od-title">
      <div className="dong-card__head">
        <h2 id="dong-od-title" className="section-title">{tx("District OD: {name}", { name: name || code })}</h2>
      </div>
      <p className="note">{tx("All modes, typical day, from TOPIS district OD.")}</p>

      <div role="group" aria-label={tx("Day type")} className="segmented segmented--fill">
        {DAY_TYPES.map((d) => (
          <button key={d} type="button" aria-pressed={dayType === d} onClick={() => setDayType(d)}>
            {tx(DAY_NAMES[d])}
          </button>
        ))}
      </div>
      <div role="group" aria-label={tx("View")} className="segmented segmented--fill">
        <button type="button" aria-pressed={view === "from"} onClick={() => setView("from")}>{tx("Where they go")}</button>
        <button type="button" aria-pressed={view === "to"} onClick={() => setView("to")}>{tx("Where they come from")}</button>
        <button type="button" aria-pressed={view === "routes"} onClick={() => setView("routes")}>{tx("Bus routes")}</button>
      </div>

      {state === "loading" && <p className="note">{tx("Loading…")}</p>}
      {state === "error" && <p className="field-error">{error}</p>}

      {state === "done" && data && view !== "routes" && (
        <>
          <p className="note num">
            {tx("Departures {dep} · arrivals {arr} a day", { dep: fmt(data.departures), arr: fmt(data.arrivals) })}
          </p>
          <ol className="flow-list">
            {data.flows.map((f) => (
              <li key={f.code}>
                <span>
                  {f.code === code || f.code === "outside" ? (
                    placeName(f)
                  ) : (
                    <button type="button" className="link-button" onClick={() => onPickDistrict?.(f.code)}>
                      {placeName(f)}
                    </button>
                  )}
                  {f.code === code ? ` (${tx("same district")})` : ""}
                </span>
                <span className="num">
                  {fmt(f.total)}
                  <span className="note"> {tx("subway {s} · bus {b}", { s: fmt(f.subway), b: fmt(f.bus) })}</span>
                </span>
              </li>
            ))}
          </ol>
        </>
      )}

      {state === "done" && data && view === "routes" && (
        <>
          <p className="note num">
            {tx("{count} routes, {riders} riders boarding here a day", {
              count: data.routes.length,
              riders: fmt(data.boardings)
            })}
          </p>
          {data.routes.length === 0 ? (
            <p className="note">{tx("No bus riders board here.")}</p>
          ) : (
            <ol className="flow-list">
              {data.routes.slice(0, 15).map((r) => (
                <li key={r.route} className="dong-route">
                  <span className="label-strong">{r.route}</span>
                  <span className="num">{fmt(r.boardings)}</span>
                  <span className="note dong-route__dests">
                    {r.destinations.map((d) => `${d.name || d.code} ${fmt(d.passengers)}`).join(" · ")}
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
