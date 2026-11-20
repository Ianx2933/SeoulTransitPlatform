import { useI18n } from "../i18n/I18nProvider.jsx";
import { hoursRange } from "../utils/basis.js";
import { busRouteStyle } from "../utils/busRouteType.js";
import CsvButton from "./CsvButton.jsx";
import RouteLoadLegend from "./RouteLoadLegend.jsx";

/**
 * Controls shared by every road-load view (district panel, route analysis):
 * the minimum riders, the summary, the ten-step scale, the CSV, the route
 * ticks with "Clear all", and riders that could not be drawn.
 *
 * extra sits next to the minimum (the district view's boundary switch);
 * children sit between the scale and the routes (a view's own hint). Route
 * ticks show when there are at least minRoutesToPick routes. hours (from the
 * view's basis) turns "a day" into the boarding hours.
 */
export default function RoadLoadControls({
  result,
  styled,
  minLoad,
  onMinLoad,
  routes,
  picked,
  onPicked,
  csvName,
  extra,
  minRoutesToPick = 1,
  hours = null,
  children
}) {
  const { tx, lang } = useI18n();
  const fmt = (v) => Number(v || 0).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const toggle = (route) => onPicked(picked.includes(route) ? picked.filter((r) => r !== route) : [...picked, route]);

  return (
    <>
      <div className="road-controls">
        <label className="field-inline">
          {tx("Hide segments under")}
          <input
            type="number"
            min="0"
            step="1"
            inputMode="numeric"
            value={minLoad}
            onChange={(e) => onMinLoad(Math.max(0, Number(e.target.value) || 0))}
          />
          {tx("riders")}
        </label>
        {extra}
      </div>
      <p className="note num">
        {hours
          ? tx("{riders} riders boarding {hours}; {shown} segments shown, {hidden} under the minimum hidden", {
              riders: fmt(result.placedPassengers ?? result.passengers),
              hours: hoursRange(hours),
              shown: styled.shown.toLocaleString(),
              hidden: styled.hidden.toLocaleString()
            })
          : tx("{riders} riders a day; {shown} segments shown, {hidden} under the minimum hidden", {
              riders: fmt(result.placedPassengers ?? result.passengers),
              shown: styled.shown.toLocaleString(),
              hidden: styled.hidden.toLocaleString()
            })}
      </p>
      <RouteLoadLegend styled={styled} minLoad={minLoad} hours={hours} />
      <CsvButton result={result} fileName={csvName} />
      {styled.shown === 0 && <p className="note">{tx("No bus riders on the road for this choice.")}</p>}
      {children}

      {routes.length >= minRoutesToPick && (
        <fieldset className="route-picks">
          <legend className="flow-title">
            {picked.length === 0
              ? tx("Routes ({count}) — tick to show only those", { count: routes.length })
              : tx("Showing {picked} of {count} routes", { picked: picked.length, count: routes.length })}
          </legend>
          <button type="button" className="link-button" disabled={picked.length === 0} onClick={() => onPicked([])}>
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
            routes: (result.unplacedRoutes || []).map((u) => u.route).join(", ")
          })}
        </p>
      )}
    </>
  );
}
