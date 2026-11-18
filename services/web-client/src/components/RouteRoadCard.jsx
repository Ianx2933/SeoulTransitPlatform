import { useEffect, useState } from "react";

import { useI18n } from "../i18n/I18nProvider.jsx";
import { busRouteStyle } from "../utils/busRouteType.js";
import { busiestRoutes } from "../utils/routeLoad.js";
import { csvFileName } from "../utils/roadCsv.js";
import CsvButton from "./CsvButton.jsx";
import RouteLoadLegend from "./RouteLoadLegend.jsx";

/** Routes one road-load request may add up (RoadLoadService.MAX_ROUTES). */
export const MAX_COMBINED_ROUTES = 20;

/**
 * Route analysis map in demand colours: the route (and any routes added to
 * it) drawn by riders on board, segment by segment, summed where routes share
 * a road. Routes can be ticked to show only those; a chosen stop can be looked
 * up across every route that serves it. All state lives in RouteAnalysisView.
 */
export default function RouteRoadCard({
  routeName,
  on,
  setOn,
  extraRoutes,
  setExtraRoutes,
  state,
  error,
  result,
  styled,
  minLoad,
  setMinLoad,
  picked,
  setPicked,
  stop,
  onStopLookup
}) {
  const { tx, lang } = useI18n();
  const [draft, setDraft] = useState(extraRoutes.join(", "));
  // Routes added from outside (a segment look-up) show up in the box too.
  useEffect(() => setDraft(extraRoutes.join(", ")), [extraRoutes]);
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const routes = result ? busiestRoutes(result.segments) : [];
  const applyDraft = () =>
    setExtraRoutes(
      draft
        .split(",")
        .map((s) => s.trim())
        .filter((s) => s && s !== routeName)
        .slice(0, MAX_COMBINED_ROUTES - 1)
    );
  const toggle = (r) => setPicked((prev) => (prev.includes(r) ? prev.filter((x) => x !== r) : [...prev, r]));

  return (
    <section className="dong-card" aria-labelledby="road-card-title">
      <div className="dong-card__head">
        <h2 id="road-card-title" className="section-title">{tx("Riders on the road")}</h2>
        <button type="button" className="chip" aria-pressed={on} onClick={() => setOn((v) => !v)}>
          {on ? tx("Route colours") : tx("Demand colours")}
        </button>
      </div>
      {on && (
        <>
          <form
            className="road-controls"
            onSubmit={(e) => {
              e.preventDefault();
              applyDraft();
            }}
          >
            <label className="field-inline">
              {tx("Add routes")}
              <input
                type="text"
                className="field-inline__wide"
                placeholder="440, 333"
                value={draft}
                onChange={(e) => setDraft(e.target.value)}
              />
            </label>
            <button type="submit" className="chip">{tx("Apply")}</button>
          </form>
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
          {state === "loading" && <p className="note">{tx("Loading…")}</p>}
          {state === "error" && <p className="field-error">{error}</p>}
          {result && styled && (
            <>
              <p className="note num">
                {tx("{riders} riders a day; {shown} segments shown, {hidden} under the minimum hidden", {
                  riders: fmt(result.placedPassengers),
                  shown: styled.shown.toLocaleString(),
                  hidden: styled.hidden.toLocaleString()
                })}
              </p>
              <RouteLoadLegend styled={styled} minLoad={minLoad} />
              <CsvButton result={result} fileName={csvFileName("routes", (result.routes || []).join("_"), result.dayType)} />
              <p className="note">{tx("Click a segment on the map to see who passes it on every route.")}</p>
              {routes.length > 1 && (
                <fieldset className="route-picks">
                  <legend className="flow-title">
                    {picked.length === 0
                      ? tx("Routes ({count}) — tick to show only those", { count: routes.length })
                      : tx("Showing {picked} of {count} routes", { picked: picked.length, count: routes.length })}
                  </legend>
                  <button type="button" className="link-button" disabled={picked.length === 0} onClick={() => setPicked([])}>
                    {tx("Clear all")}
                  </button>
                  <ul className="route-picks__list">
                    {routes.map((r) => (
                      <li key={r.route}>
                        <label className="check">
                          <input type="checkbox" checked={picked.includes(r.route)} onChange={() => toggle(r.route)} />
                          <span className="route-chip__dot" style={{ background: busRouteStyle(r.route).bg }} aria-hidden="true" />
                          <span className="label-strong">{r.route}</span>
                        </label>
                        <span className="num note">{tx("busiest {riders}", { riders: fmt(r.load) })}</span>
                      </li>
                    ))}
                  </ul>
                </fieldset>
              )}
              {result.unplacedPassengers > 0 && (
                <p className="note num">
                  {tx("Not drawn: {riders} riders on routes without a stored path ({routes})", {
                    riders: fmt(result.unplacedPassengers),
                    routes: result.unplacedRoutes.map((u) => u.route).join(", ")
                  })}
                </p>
              )}
            </>
          )}
          {stop?.ars && (
            <div className="road-stop">
              <p className="note">{tx("Stop {name}, every route that serves it:", { name: stop.name || stop.ars })}</p>
              <div className="chip-row">
                <button type="button" className="chip" onClick={() => onStopLookup("board")}>{tx("Who boards here")}</button>
                <button type="button" className="chip" onClick={() => onStopLookup("alight")}>{tx("Who gets off here")}</button>
              </div>
            </div>
          )}
        </>
      )}
    </section>
  );
}
