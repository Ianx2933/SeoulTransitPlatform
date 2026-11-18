import { useEffect, useMemo, useRef, useState } from "react";

import {
  fetchRouteSegment,
  fetchRouteStop,
  fetchRouteStops
} from "../api/routeAnalysisApi.js";
import { useI18n } from "../i18n/I18nProvider.jsx";
import { busRouteStyle, busRouteType } from "../utils/busRouteType.js";
import { buildBarChart, findTurnaround, percent } from "../utils/routeLoadChart.js";
import { csvFileName, routeStopsCsv } from "../utils/roadCsv.js";
import CsvButton from "./CsvButton.jsx";

const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];
const HOURS = Array.from({ length: 24 }, (_, h) => h);
const CHART = { width: 440, height: 200 };
const DEFAULT_REACH = "100";
const REACH_DELAY_MS = 400;

const pad2 = (h) => String(h).padStart(2, "0");

/**
 * Route analysis on the typical-day OD.
 *
 * Pick a route and a day type (and, on weekdays, an hour) to see riders on
 * board along the route. Click a stop for where its riders go and come from
 * and how far its load carries past a threshold you type; pick two stops for
 * the demand inside the section and crossing its ends.
 */
export default function RouteAnalysisPanel({
  busLines = [],
  stopSeq,
  onStopSeqChange,
  onRouteData,
  onReach,
  onTurnaround,
  onSegment,
  onQuery,
  turnaroundFromBis = null
}) {
  const { t, lang } = useI18n();
  const fmt = (value) =>
    value === null || value === undefined
      ? "-"
      : Number(value).toLocaleString(lang === "ko" ? "ko-KR" : "en-US", { maximumFractionDigits: 1 });

  const [route, setRoute] = useState("");
  const [dayType, setDayType] = useState("weekday");
  const [hour, setHour] = useState(null);
  const [routeData, setRouteData] = useState(null);
  const setStopSeq = onStopSeqChange;
  const [stopDetail, setStopDetail] = useState(null);
  const [reachText, setReachText] = useState(DEFAULT_REACH);
  const [segment, setSegment] = useState(null);
  const [fromSeq, setFromSeq] = useState("");
  const [toSeq, setToSeq] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const hourOk = dayType === "weekday";
  const activeHour = hourOk ? hour : null;
  const reachValue = Number(reachText);
  const reachValid = reachText.trim() !== "" && Number.isFinite(reachValue) && reachValue >= 1;

  const messagesFor = (name) => ({
    defaultMessage: t("route.failed"),
    notFoundMessage: activeHour !== null ? t("route.notFoundHour", { route: name }) : t("route.notFound", { route: name })
  });

  const chart = useMemo(() => buildBarChart(routeData?.stops || [], CHART), [routeData]);
  const estimatedTurnaround = useMemo(() => findTurnaround(routeData?.stops), [routeData]);
  // The city's stop list marks the turnaround (transYn); the estimate is the fallback.
  const turnaround = turnaroundFromBis ?? estimatedTurnaround;

  useEffect(() => {
    onQuery?.({ dayType, hour: activeHour });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dayType, activeHour]);

  // Tell the map what to draw.
  useEffect(() => {
    onRouteData?.(routeData);
    onTurnaround?.(turnaround);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeData, turnaround]);

  const run = async (task) => {
    setLoading(true);
    setError("");
    try {
      await task();
    } catch (e) {
      setError(e.message);
    } finally {
      setLoading(false);
    }
  };

  const loadRoute = (name, keepStop) =>
    run(async () => {
      const data = await fetchRouteStops({ route: name, dayType, hour: activeHour, messages: messagesFor(name) });
      setRouteData(data);
      setSegment(null);
      const seqs = data.stops.map((s) => s.seq);
      if (!keepStop || !seqs.includes(stopSeq)) {
        setStopSeq(null);
        setStopDetail(null);
        setFromSeq(String(seqs[0]));
        setToSeq(String(seqs[Math.min(seqs.length - 1, 10)]));
      }
    });

  const onSubmit = (event) => {
    event.preventDefault();
    const name = route.trim();
    if (!name) {
      setError(t("route.enterRoute"));
      return;
    }
    loadRoute(name, false);
  };

  // Day type or hour changed: reload the route already on screen.
  const firstRun = useRef(true);
  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false;
      return;
    }
    if (routeData) loadRoute(routeData.노선명, true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dayType, activeHour]);

  // Stop detail follows the chosen stop, the time of day and the threshold,
  // with a short delay while the threshold is being typed.
  useEffect(() => {
    if (!routeData || stopSeq === null || !reachValid) return undefined;
    const timer = setTimeout(() => {
      run(async () => {
        setStopDetail(
          await fetchRouteStop({
            route: routeData.노선명,
            dayType,
            hour: activeHour,
            seq: stopSeq,
            reach: [reachValue],
            messages: messagesFor(routeData.노선명)
          })
        );
      });
    }, REACH_DELAY_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeData, stopSeq, reachValue, reachValid]);

  const loadSegment = () =>
    run(async () => {
      setSegment(
        await fetchRouteSegment({
          route: routeData.노선명,
          dayType,
          hour: activeHour,
          from: Number(fromSeq),
          to: Number(toSeq),
          messages: messagesFor(routeData.노선명)
        })
      );
    });

  const segmentSeqs = new Set(segment ? segment.stops.map((s) => s.seq) : []);
  const reach = stopDetail && stopDetail.seq === stopSeq && reachValid ? stopDetail.reach?.[0] : null;
  const reachSeq = reach?.untilSeq ?? null;

  useEffect(() => {
    onReach?.(reachSeq);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [reachSeq]);
  useEffect(() => {
    onSegment?.(segment ? [segment.fromSeq, segment.toSeq] : null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [segment]);
  const bandBars =
    stopSeq !== null && reachSeq !== null
      ? chart.bars.filter((b) => b.seq >= stopSeq && b.seq <= reachSeq)
      : [];

  return (
    <section className="route-panel" aria-labelledby="route-title">
      <header className="route-panel__head">
        {routeData ? <RouteSign name={routeData.노선명} /> : null}
        <h1 id="route-title" className="route-panel__title">{t("route.title")}</h1>
      </header>

      <form onSubmit={onSubmit} className="route-search">
        <label className="field">
          <span>{t("route.searchLabel")}</span>
          <input
            list="route-analysis-bus-lines"
            value={route}
            onChange={(e) => setRoute(e.target.value)}
            placeholder={t("route.searchPlaceholder")}
            inputMode="search"
          />
          <datalist id="route-analysis-bus-lines">
            {busLines.slice(0, 2000).map((line) => (
              <option key={line} value={line} />
            ))}
          </datalist>
        </label>
        <button type="submit" className="button button--primary" disabled={loading}>
          {loading ? t("route.loading") : t("route.submit")}
        </button>
      </form>

      <div role="group" aria-label={t("dayType.label")} className="segmented segmented--fill">
        {DAY_TYPES.map((d) => (
          <button key={d} type="button" aria-pressed={dayType === d} onClick={() => setDayType(d)}>
            {t(`dayType.${d}`)}
          </button>
        ))}
      </div>

      <div className="hour-picker">
        <div className="hour-picker__head">
          <span className="label-strong">{t("route.hour.label")}</span>
          <span className="num">
            {activeHour === null ? t("route.hour.allDay") : t("route.hour.range", { from: pad2(activeHour), to: pad2(activeHour + 1) })}
          </span>
        </div>
        <div className="hour-picker__row">
          <button
            type="button"
            className="chip"
            aria-pressed={activeHour === null}
            onClick={() => setHour(null)}
          >
            {t("route.hour.allDay")}
          </button>
          <div role="group" aria-label={t("route.hour.pick")} className="hour-grid">
            {HOURS.map((h) => (
              <button
                key={h}
                type="button"
                disabled={!hourOk}
                aria-pressed={activeHour === h}
                onClick={() => setHour(activeHour === h ? null : h)}
                className="hour-cell num"
              >
                {pad2(h)}
              </button>
            ))}
          </div>
        </div>
        <p className="note">{hourOk ? t("route.hour.meaning") : t("route.hour.weekdayOnly")}</p>
      </div>

      {error && <div className="error-box" role="alert">{error}</div>}

      {!routeData && !error && <p className="note">{t("route.empty")}</p>}

      {routeData && (
        <>
          <p className="route-summary num">
            {activeHour === null
              ? t("route.summary", { total: fmt(routeData.totalPassengers), stops: routeData.stops.length, days: routeData.nDays ?? "-" })
              : t("route.summaryHour", {
                  total: fmt(routeData.totalPassengers),
                  stops: routeData.stops.length,
                  hour: t("route.hour.range", { from: pad2(activeHour), to: pad2(activeHour + 1) })
                })}
            {routeData.unorderedPassengers > 0 && (
              <span className="note"> · {t("route.unordered", { value: fmt(routeData.unorderedPassengers) })}</span>
            )}
          </p>

          <div className="chart-block">
            <h2 className="section-title">{t("route.chart.title")}</h2>
            <div className="legend">
              <span><i className="swatch swatch--up" />{t("route.chart.up")}</span>
              {turnaround != null && <span><i className="swatch swatch--down" />{t("route.chart.down")}</span>}
              <span className="note num">{t("route.chart.max", { value: fmt(chart.maxLoad) })}</span>
            </div>
            <svg
              viewBox={`0 0 ${CHART.width} ${CHART.height}`}
              className="load-chart"
              role="img"
              aria-label={t("route.chart.aria")}
            >
              <defs>
                <linearGradient id="grad-up" x1="0" y1="1" x2="0" y2="0">
                  <stop offset="0" stopColor="var(--route-up-light)" />
                  <stop offset="1" stopColor="var(--route-up)" />
                </linearGradient>
                <linearGradient id="grad-down" x1="0" y1="1" x2="0" y2="0">
                  <stop offset="0" stopColor="var(--route-down-light)" />
                  <stop offset="1" stopColor="var(--route-down)" />
                </linearGradient>
              </defs>
              <line x1={40} x2={CHART.width - 12} y1={chart.baseY} y2={chart.baseY} stroke="var(--rule)" />
              {bandBars.length > 0 && (
                <rect
                  x={bandBars[0].x}
                  y={chart.baseY + 2}
                  width={bandBars[bandBars.length - 1].x + bandBars[bandBars.length - 1].w - bandBars[0].x}
                  height={6}
                  rx={3}
                  fill="var(--selected)"
                />
              )}
              {chart.bars.map((b, index) => {
                const down = turnaround != null && b.seq > turnaround;
                const fill =
                  b.seq === stopSeq
                    ? "var(--selected)"
                    : segmentSeqs.has(b.seq)
                      ? "var(--selected-light)"
                      : down
                        ? "url(#grad-down)"
                        : "url(#grad-up)";
                const showLabel =
                  index % chart.labelEvery === 0 || index === chart.bars.length - 1 || b.seq === turnaround;
                return (
                  <g key={b.seq} onClick={() => setStopSeq(b.seq)} className="bar">
                    <rect x={b.x} y={b.y} width={b.w} height={Math.max(b.h, 0.5)} fill={fill} rx={1}>
                      <title>
                        {t("route.chart.bar", {
                          seq: b.seq,
                          name: b.stop.name,
                          onBoard: fmt(b.stop.onBoardAfter),
                          on: fmt(b.stop.boardings),
                          off: fmt(b.stop.alightings)
                        })}
                      </title>
                    </rect>
                    {showLabel && (
                      <text x={b.cx} y={chart.baseY + 20} fontSize="10" textAnchor="middle" className="axis-label">
                        {b.seq}
                      </text>
                    )}
                  </g>
                );
              })}
              {turnaround != null &&
                chart.bars
                  .filter((b) => b.seq === turnaround)
                  .map((b) => (
                    <g key="turnaround">
                      <line x1={b.cx} x2={b.cx} y1={16} y2={chart.baseY} stroke="var(--ink)" strokeDasharray="4 3" />
                      <text x={b.cx + 4} y={14} fontSize="11" className="axis-label axis-label--strong">
                        {turnaroundFromBis != null
                          ? t("route.chart.turnaroundBis", { seq: b.seq })
                          : t("route.chart.turnaround", { seq: b.seq })}
                      </text>
                    </g>
                  ))}
            </svg>
            <p className="note">{t("route.chart.hint")}</p>
          </div>

          {stopSeq !== null && (
            <StopCard
              detail={stopDetail && stopDetail.seq === stopSeq ? stopDetail : null}
              seq={stopSeq}
              reachText={reachText}
              setReachText={setReachText}
              reachValid={reachValid}
              fmt={fmt}
            />
          )}

          <div className="card">
            <h2 className="section-title">{t("route.segment.heading")}</h2>
            <div className="route-search">
              <label className="field">
                <span>{t("route.segment.from")}</span>
                <StopSelect stops={routeData.stops} value={fromSeq} onChange={setFromSeq} />
              </label>
              <label className="field">
                <span>{t("route.segment.to")}</span>
                <StopSelect stops={routeData.stops} value={toSeq} onChange={setToSeq} />
              </label>
              <button type="button" className="button" onClick={loadSegment} disabled={loading}>
                {t("route.segment.submit")}
              </button>
            </div>
            {segment && <SegmentView segment={segment} fmt={fmt} />}
          </div>

          <details className="card">
            <summary>{t("route.table.summary", { count: routeData.stops.length })}</summary>
            <CsvButton
              result={routeData}
              toCsv={routeStopsCsv}
              fileName={csvFileName("route", routeData.노선명, "stops", routeData.기준일자)}
            />
            <div className="table-wrap">
              <table className="data-table">
                <thead>
                  <tr>
                    <th scope="col">{t("route.table.seq")}</th>
                    <th scope="col">{t("route.table.name")}</th>
                    <th scope="col">{t("route.table.ars")}</th>
                    <th scope="col" className="num">{t("route.table.on")}</th>
                    <th scope="col" className="num">{t("route.table.off")}</th>
                    <th scope="col" className="num">{t("route.table.onBoard")}</th>
                  </tr>
                </thead>
                <tbody>
                  {routeData.stops.map((s) => (
                    <tr
                      key={s.seq}
                      onClick={() => setStopSeq(s.seq)}
                      className={s.seq === stopSeq ? "is-selected" : undefined}
                    >
                      <td className="num">{s.seq}</td>
                      <td>{s.name}</td>
                      <td className="num">{s.ars}</td>
                      <td className="num">{fmt(s.boardings)}</td>
                      <td className="num">{fmt(s.alightings)}</td>
                      <td className="num">{fmt(s.onBoardAfter)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </details>

          <p className="note">{t("route.source")}</p>
        </>
      )}
    </section>
  );
}

function RouteSign({ name }) {
  const { t } = useI18n();
  const style = busRouteStyle(name);
  return (
    <span className="route-sign" style={{ background: style.bg, color: style.fg }}>
      <span className="visually-hidden">{t(`busType.${busRouteType(name)}`)} </span>
      {name}
    </span>
  );
}

function StopCard({ detail, seq, reachText, setReachText, reachValid, fmt }) {
  const { t } = useI18n();
  const reach = detail?.reach?.[0];
  return (
    <div className="card card--stop">
      <span className="note">{t("route.stop.heading", { seq })}</span>
      <h2 className="stop-name">{detail ? detail.name : "…"}</h2>
      {detail && (
        <dl className="figures">
          <div><dt>{t("route.stop.alight")}</dt><dd className="num">{fmt(detail.alightings)}</dd></div>
          <div className="figures__main"><dt>{t("route.stop.onBoard")}</dt><dd className="num">{fmt(detail.onBoardAfter)}</dd></div>
          <div><dt>{t("route.stop.board")}</dt><dd className="num">{fmt(detail.boardings)}</dd></div>
        </dl>
      )}

      <h3 className="section-title section-title--small">{t("route.reach.heading")}</h3>
      <label className="reach-input">
        <span>{t("route.reach.label")}</span>
        <span className="reach-input__box">
          <input
            type="number"
            inputMode="numeric"
            min="1"
            step="1"
            value={reachText}
            onChange={(e) => setReachText(e.target.value)}
            aria-label={t("route.reach.input")}
            aria-invalid={!reachValid}
            className="num"
          />
          <span>{t("route.reach.unit")}</span>
        </span>
      </label>
      {!reachValid && <p className="field-error">{t("route.reach.invalid")}</p>}
      {reachValid && reach && (
        <div className="reach-result" aria-live="polite">
          {reach.untilSeq == null ? (
            <span>{t("route.reach.never", { value: fmt(reach.level) })}</span>
          ) : (
            <>
              <span className="note">{t("route.reach.result", { value: fmt(reach.level) })}</span>
              <strong>{reach.untilSeq} {reach.untilName}</strong>
              <span className="note num">{t("route.reach.more", { count: reach.untilSeq - seq })}</span>
            </>
          )}
        </div>
      )}

      {detail && (
        <div className="two-col">
          <FlowList title={t("route.stop.destinations")} flows={detail.destinations} fmt={fmt} />
          <FlowList title={t("route.stop.origins")} flows={detail.origins} fmt={fmt} />
        </div>
      )}
    </div>
  );
}

function StopSelect({ stops, value, onChange }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)}>
      {stops.map((s) => (
        <option key={s.seq} value={s.seq}>
          {s.seq} {s.name}
        </option>
      ))}
    </select>
  );
}

function SegmentView({ segment, fmt }) {
  const { t } = useI18n();
  const total = segment.routeTotal;
  const rows = [
    ["within", segment.within],
    ["leaving", segment.leaving],
    ["entering", segment.entering],
    ["through", segment.through]
  ];
  return (
    <div className="segment">
      <p className="label-strong">
        {t("route.segment.title", {
          from: segment.fromSeq,
          fromName: segment.fromName,
          to: segment.toSeq,
          toName: segment.toName
        })}
      </p>
      <dl className="rows">
        {rows.map(([key, value]) => (
          <div key={key}>
            <dt>{t(`route.segment.${key}`)}</dt>
            <dd className="num">
              {fmt(value)} <span className="note">{t("route.segment.share", { value: percent(value, total) })}</span>
            </dd>
          </div>
        ))}
      </dl>
      <div className="two-col">
        <FlowList title={t("route.segment.leavingTo")} flows={segment.leavingTo} fmt={fmt} />
        <FlowList title={t("route.segment.enteringFrom")} flows={segment.enteringFrom} fmt={fmt} />
      </div>
    </div>
  );
}

function FlowList({ title, flows, fmt }) {
  const { t } = useI18n();
  return (
    <div>
      <h4 className="flow-title">{title}</h4>
      {flows.length === 0 ? (
        <p className="note">{t("route.stop.none")}</p>
      ) : (
        <ol className="flow-list">
          {flows.map((f) => (
            <li key={f.seq}>
              <span>{f.seq} {f.name}</span>
              <span className="num">{fmt(f.passengers)}</span>
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}
