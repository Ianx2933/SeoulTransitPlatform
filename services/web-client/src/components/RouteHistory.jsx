import { useEffect, useState } from "react";

import { fetchRouteHistory, fetchRouteStops } from "../api/routeAnalysisApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { monthsList, periodParam } from "../utils/basis.js";
import { compareStops, loadSummary } from "../utils/routeCompare.js";

const SHOWN_STOPS = 6;

/**
 * When the route changed, from the Seoul route master snapshots (about
 * monthly): stops added and removed, when it was last seen. A change offers a
 * before and after comparison.
 */
export function RouteHistory({ route, onCompare }) {
  const { t } = useI18n();
  const [state, setState] = useState({ route: null, data: null, error: "" });
  useEffect(() => {
    if (!route) return undefined;
    let ignore = false;
    fetchRouteHistory(route)
      .then((data) => {
        if (!ignore) setState({ route, data, error: "" });
      })
      .catch((e) => {
        if (!ignore) setState({ route, data: null, error: e.message });
      });
    return () => {
      ignore = true;
    };
  }, [route]);

  const entries = state.route === route ? state.data?.entries || [] : [];
  const names = (stops) =>
    stops.length > SHOWN_STOPS
      ? `${stops.slice(0, SHOWN_STOPS).map((s) => s.name).join(", ")} ${t("route.history.more", { value: stops.length - SHOWN_STOPS })}`
      : stops.map((s) => s.name).join(", ");

  return (
    <details className="route-history">
      <summary>{t("route.history.title")}</summary>
      {state.route === route && state.error && <p className="note">{t("route.history.error")}</p>}
      {state.route === route && !state.error && entries.length === 0 && <p className="note">{t("route.history.none")}</p>}
      <ol className="route-history__list">
        {entries.map((e, i) => (
          <li key={`${e.kind}-${e.date}-${i}`}>
            {e.kind === "first" && t("route.history.first", { date: e.date, stops: e.stops })}
            {e.kind === "ended" && t("route.history.ended", { date: e.date })}
            {(e.kind === "changed" || e.kind === "today") && (
              <>
                <span>
                  {e.kind === "changed"
                    ? t("route.history.changed", { since: e.since, date: e.date, stops: e.stops })
                    : t("route.history.today", { since: e.since, stops: e.stops })}
                </span>
                {e.added.length > 0 && (
                  <span className="route-history__stops route-history__stops--added">
                    {t("route.history.added", { value: e.added.length, list: names(e.added) })}
                  </span>
                )}
                {e.removed.length > 0 && (
                  <span className="route-history__stops route-history__stops--removed">
                    {t("route.history.removed", { value: e.removed.length, list: names(e.removed) })}
                  </span>
                )}
                {e.reordered && <span className="note">{t("route.history.reordered")}</span>}
                <button type="button" className="chip" onClick={() => onCompare(e)}>
                  {t("route.history.compare")}
                </button>
              </>
            )}
          </li>
        ))}
      </ol>
      <p className="note">{t("route.history.source")}</p>
    </details>
  );
}

/**
 * The route on screen (a) against the same route in other months (b): day
 * totals, the busiest on-board count, and boardings stop by stop, with the
 * stops only one side has.
 */
export function RouteCompare({ route, dayType, basis, current, compare, setCompare, range }) {
  const { t, lang } = useI18n();
  const fmt = (v) =>
    v === null || v === undefined ? "–" : Number(v).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });
  const [state, setState] = useState({ key: null, data: null, error: "" });
  const period = compare.period;
  const key = `${route}|${dayType}|${periodParam(period)}|${basis.hours ? `${basis.hours.from}-${basis.hours.to}` : ""}`;
  useEffect(() => {
    let ignore = false;
    fetchRouteStops({
      route,
      dayType,
      basis: { ...basis, period },
      messages: { defaultMessage: t("route.compare.failed"), notFoundMessage: t("route.compare.notFound") }
    })
      .then((data) => {
        if (!ignore) setState({ key, data, error: "" });
      })
      .catch((e) => {
        if (!ignore) setState({ key, data: null, error: e.message });
      });
    return () => {
      ignore = true;
    };
    // key stands for route, day type, period and hours.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key]);

  const months = range ? monthsList(range.first, range.last) : [];
  const setEnd = (end, value) => {
    const next = { ...period, [end]: value };
    if (next.from > next.to) next[end === "from" ? "to" : "from"] = value;
    setCompare({ ...compare, period: next, uncertain: false });
  };
  const data = state.key === key ? state.data : null;
  const a = loadSummary(current);
  const b = data ? loadSummary(data) : null;
  const rows = data ? compareStops(current?.stops, data.stops) : [];
  const change = (x, y) => (x > 0 ? `${y >= x ? "+" : ""}${Math.round(((y - x) / x) * 100)}%` : "");
  const aLabel = basis.period ? periodParam(basis.period).replace("..", "–") : t("route.compare.typical");

  return (
    <section className="route-compare" aria-label={t("route.compare.title")}>
      <div className="route-compare__head">
        <h2 className="section-title">{t("route.compare.title")}</h2>
        <button type="button" className="link-button" onClick={() => setCompare(null)}>
          {t("route.compare.close")}
        </button>
      </div>
      <div className="road-controls">
        <span className="note">{t("route.compare.a", { period: aLabel })}</span>
        <label className="field-inline">
          {t("route.compare.b")}
          <select value={period.from} onChange={(e) => setEnd("from", e.target.value)}>
            {months.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
          –
          <select value={period.to} onChange={(e) => setEnd("to", e.target.value)}>
            {months.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </select>
        </label>
      </div>
      {compare.uncertain && <p className="note">{t("route.compare.uncertain")}</p>}
      {state.key === key && state.error && <div className="error-box" role="alert">{state.error}</div>}
      {b && (
        <>
          <table className="compare-summary num">
            <thead>
              <tr>
                <th />
                <th>A</th>
                <th>B</th>
                <th />
              </tr>
            </thead>
            <tbody>
              <tr>
                <th>{t("route.compare.total")}</th>
                <td>{fmt(a.total)}</td>
                <td>{fmt(b.total)}</td>
                <td>{change(a.total, b.total)}</td>
              </tr>
              <tr>
                <th>{t("route.compare.maxOnBoard")}</th>
                <td>{fmt(a.maxOnBoard)}</td>
                <td>{fmt(b.maxOnBoard)}</td>
                <td>{change(a.maxOnBoard, b.maxOnBoard)}</td>
              </tr>
              <tr>
                <th>{t("route.compare.stops")}</th>
                <td>{a.stops}</td>
                <td>{b.stops}</td>
                <td />
              </tr>
            </tbody>
          </table>
          <div className="compare-table-wrap">
            <table className="compare-table num">
              <thead>
                <tr>
                  <th>{t("route.compare.stop")}</th>
                  <th>{t("route.compare.boardA")}</th>
                  <th>{t("route.compare.boardB")}</th>
                  <th>{t("route.compare.diff")}</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.key} className={`compare-row compare-row--${r.status}`}>
                    <td>
                      {r.name}
                      {r.status !== "both" && <span className="compare-tag"> {t(`route.compare.${r.status}`)}</span>}
                    </td>
                    <td>{fmt(r.a)}</td>
                    <td>{fmt(r.b)}</td>
                    <td>{r.change === null ? "" : `${r.change >= 0 ? "+" : ""}${fmt(r.change)}`}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}
    </section>
  );
}
