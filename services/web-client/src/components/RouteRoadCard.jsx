import { useEffect, useState } from "react";

import { useI18n } from "../i18n/I18nProvider.jsx";
import { basisFileTag, isPeriodUnavailable } from "../utils/basis.js";
import { csvFileName } from "../utils/roadCsv.js";
import { busiestRoutes } from "../utils/routeLoad.js";
import RoadLoadControls from "./RoadLoadControls.jsx";

/** Routes one road-load request may add up (RoadLoadService.MAX_ROUTES). */
export const MAX_COMBINED_ROUTES = 20;

/**
 * Route analysis map in demand colours: the route (and any routes added to
 * it) drawn by riders on board, segment by segment, summed where routes share
 * a road. Routes can be ticked to show only those; a chosen stop can be looked
 * up across every route that serves it. All state lives in RouteAnalysisView;
 * the shared parts are RoadLoadControls.
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
  onStopLookup,
  basis = null
}) {
  const { tx } = useI18n();
  const [draft, setDraft] = useState(extraRoutes.join(", "));
  // Routes added from outside (a segment look-up) show up in the box too.
  useEffect(() => setDraft(extraRoutes.join(", ")), [extraRoutes]);
  const routes = result ? busiestRoutes(result.segments) : [];
  const applyDraft = () =>
    setExtraRoutes(
      draft
        .split(",")
        .map((s) => s.trim())
        .filter((s) => s && s !== routeName)
        .slice(0, MAX_COMBINED_ROUTES - 1)
    );

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
          {state === "loading" && <p className="note">{tx("Loading…")}</p>}
          {state === "error" && (
            <p className="field-error">
              {isPeriodUnavailable(error) ? tx("Months and hours could not be loaded right now.") : error}
            </p>
          )}
          {result && styled && (
            <RoadLoadControls
              result={result}
              styled={styled}
              minLoad={minLoad}
              onMinLoad={setMinLoad}
              routes={routes}
              picked={picked}
              onPicked={setPicked}
              csvName={csvFileName("routes", (result.routes || []).join("_"), result.dayType, basisFileTag(basis))}
              minRoutesToPick={2}
              hours={basis?.hours || null}
            >
              <p className="note">{tx("Click a segment on the map to see who passes it on every route.")}</p>
            </RoadLoadControls>
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
