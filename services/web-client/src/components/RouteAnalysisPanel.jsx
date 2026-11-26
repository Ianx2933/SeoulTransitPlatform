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
import { basisFileTag, basisKey, isPeriodUnavailable, isPlain, periodForRoute, periodParam, TYPICAL } from "../utils/basis.js";
import { fetchPeriodRange, fetchRouteRange } from "../api/periodApi.js";
import BasisControls, { PeriodUnavailable, usePeriodRange } from "./BasisControls.jsx";
import { RouteCompare, RouteHistory } from "./RouteHistory.jsx";
import SegmentDayCompare from "./SegmentDayCompare.jsx";
import FleetInputs from "./FleetInputs.jsx";
import { parseRouteLink, routeLinkHash } from "../utils/routeLink.js";
import { comparePeriods } from "../utils/routeCompare.js";
import CsvButton from "./CsvButton.jsx";
import useLatest from "../hooks/useLatest.js";
import { sortFlows } from "../utils/flowSort.js";

const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];
const CHART = { width: 440, height: 200 };
const DEFAULT_REACH = "100";
const REACH_DELAY_MS = 400;

const pad2 = (h) => String(h).padStart(2, "0");

/**
 * Route analysis on the typical day or a period of months.
 *
 * Pick a route and a day type (and, if wanted, months and boarding hours:
 * BasisControls) to see riders on board along the route. Click a stop for where its riders go and come from
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

  // A link such as "#/route?r=3411&p=2024-09..2024-11" opens that route, day type, months and hours.
  const [link] = useState(() => (typeof window !== "undefined" ? parseRouteLink(window.location.hash) : null));
  const [route, setRoute] = useState(link?.route || "");
  const [dayType, setDayType] = useState(link?.dayType || "weekday");
  const [basis, setBasis] = useState(link?.basis || TYPICAL);
  // Before and after: the other months to compare with ({ period, uncertain }), or null.
  const [compare, setCompare] = useState(null);
  const [compareNote, setCompareNote] = useState("");
  const [copied, setCopied] = useState(false);
  // Vehicles and trips typed in by hand (no fleet data loaded); kept per route in the link.
  const [fleet, setFleet] = useState(() => link?.fleet || { vehicles: null, trips: null });
  const { range } = usePeriodRange();
  const [routeData, setRouteData] = useState(null);
  const setStopSeq = onStopSeqChange;
  const [stopDetail, setStopDetail] = useState(null);
  const [reachText, setReachText] = useState(DEFAULT_REACH);
  const [segment, setSegment] = useState(null);
  const [fromSeq, setFromSeq] = useState("");
  const [toSeq, setToSeq] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  // Set when a withdrawn route moved the period to its own months.
  const [movedNote, setMovedNote] = useState("");

  const hours = basis.hours;
  const basisId = basisKey(basis);
  const reachValue = Number(reachText);
  const reachValid = reachText.trim() !== "" && Number.isFinite(reachValue) && reachValue >= 1;

  // A route the OD splits in two (서대문02 → 서대문02대, 서대문02소; 8773 →
  // 8773출근, 8773퇴근) is not guessed by the server: name the choices instead.
  const splitInto = (name) => {
    const n = String(name || "").trim();
    return n ? busLines.filter((line) => line !== n && line.startsWith(n) && /^[가-힣]/.test(line.slice(n.length))) : [];
  };
  const messagesFor = (name) => {
    const choices = splitInto(name);
    return {
      defaultMessage: t("route.failed"),
      notFoundMessage:
        choices.length > 1
          ? t("route.notFoundSplit", { route: name, list: choices.join(", ") })
          : isPlain(basis)
            ? t("route.notFound", { route: name })
            : t("route.notFoundBasis", { route: name })
    };
  };

  // Saturday and Sunday read against the weekday: the chart's scale is the
  // route's weekday peak (same months or typical day, same hours; a single day
  // is compared with the typical weekday).
  const [weekdayMax, setWeekdayMax] = useState(null);
  const [weekdayScale, setWeekdayScale] = useState(true);
  const routeName = routeData?.노선명 || null;
  useEffect(() => {
    setWeekdayMax(null);
    if (!routeName || dayType === "weekday") return undefined;
    let ignore = false;
    const reference = basis.period?.day ? { ...basis, period: null } : basis;
    fetchRouteStops({ route: routeName, dayType: "weekday", basis: reference, messages: { defaultMessage: "weekday" } })
      .then((d) => {
        if (!ignore) setWeekdayMax(Math.max(0, ...(d.stops || []).map((s) => s.onBoardAfter || 0)) || null);
      })
      .catch(() => {});
    return () => {
      ignore = true;
    };
    // basisId stands for basis.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [routeName, dayType, basisId]);
  const scaleMax = weekdayScale && dayType !== "weekday" ? weekdayMax : null;
  const chart = useMemo(
    () => buildBarChart(routeData?.stops || [], { ...CHART, scaleMax }),
    [routeData, scaleMax]
  );
  const estimatedTurnaround = useMemo(() => findTurnaround(routeData?.stops), [routeData]);
  // The city's stop list marks the turnaround (transYn); the estimate is the fallback.
  const turnaround = turnaroundFromBis ?? estimatedTurnaround;

  // Parent callbacks are new functions on every render; effects below run on
  // their own values and call the latest callback.
  const callbacks = useLatest({ onQuery, onRouteData, onTurnaround, onReach, onSegment });

  useEffect(() => {
    callbacks.current.onQuery?.({ dayType, basis });
  }, [callbacks, dayType, basis]);

  // Tell the map what to draw.
  useEffect(() => {
    callbacks.current.onRouteData?.(routeData);
    callbacks.current.onTurnaround?.(turnaround);
  }, [callbacks, routeData, turnaround]);

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

  // A route the chosen months miss (withdrawn, renumbered: 1155): the period it ran in, or null.
  const movedPeriod = async (name) => {
    try {
      const [range, routeRange] = await Promise.all([fetchPeriodRange(), fetchRouteRange(name)]);
      return periodForRoute(routeRange, range, basis);
    } catch {
      return null;
    }
  };

  const loadRoute = (name, keepStop) =>
    run(async () => {
      let data;
      try {
        data = await fetchRouteStops({ route: name, dayType, basis, messages: messagesFor(name) });
      } catch (e) {
        // Figures of another day type or period must not stay under the error.
        setRouteData(null);
        setSegment(null);
        setStopDetail(null);
        const period = await movedPeriod(name);
        if (!period) throw e;
        const moved = { ...basis, period };
        data = await fetchRouteStops({ route: name, dayType, basis: moved, messages: messagesFor(name) });
        setBasis(moved);
        setMovedNote(t("route.movedPeriod", { route: name, from: period.from, to: period.to }));
      }
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

  // What the effects below need at the moment they run, without rerunning them.
  const current = useLatest({ run, loadRoute, messagesFor, routeData, dayType, basis });

  // A history entry: show the months before the change here, the months after it in the comparison.
  const onCompare = (entry) => {
    const p = comparePeriods(entry, range);
    if (!p) {
      setCompareNote(t("route.compare.noMonths"));
      return;
    }
    setCompareNote("");
    setBasis({ ...basis, period: p.before });
    setCompare({ period: p.after, uncertain: p.uncertain });
  };

  // The route on screen in the address, so the link opens the same view.
  useEffect(() => {
    if (!routeData || typeof window === "undefined" || !window.location.hash.startsWith("#/route")) return;
    const hash = routeLinkHash({ route: routeData.노선명, dayType, basis, fleet });
    if (window.location.hash !== hash) window.history.replaceState(null, "", hash);
  }, [routeData, dayType, basis, fleet]);

  const copyLink = () => {
    navigator.clipboard?.writeText(window.location.href).then(
      () => {
        setCopied(true);
        setTimeout(() => setCopied(false), 2000);
      },
      () => {}
    );
  };

  const onSubmit = (event) => {
    event.preventDefault();
    setMovedNote("");
    setCompare(null);
    setCompareNote("");
    if (route.trim() !== routeData?.노선명) setFleet({ vehicles: null, trips: null });
    const name = route.trim();
    if (!name) {
      setError(t("route.enterRoute"));
      return;
    }
    loadRoute(name, false);
  };

  // Opened from a link: analyse its route once.
  useEffect(() => {
    if (link?.route) current.current.loadRoute(link.route, false);
    // Once, on opening.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Day type, months or hours changed: reload the route already on screen.
  const firstRun = useRef(true);
  useEffect(() => {
    if (firstRun.current) {
      firstRun.current = false;
      return;
    }
    const { routeData: shown, loadRoute: reload } = current.current;
    if (shown) reload(shown.노선명, true);
  }, [current, dayType, basisId]);

  // Stop detail follows the chosen stop, the time of day and the threshold,
  // with a short delay while the threshold is being typed.
  useEffect(() => {
    if (!routeData || stopSeq === null || !reachValid) return undefined;
    const timer = setTimeout(() => {
      const now = current.current;
      now.run(async () => {
        setStopDetail(
          await fetchRouteStop({
            route: routeData.노선명,
            dayType: now.dayType,
            basis: now.basis,
            seq: stopSeq,
            reach: [reachValue],
            messages: now.messagesFor(routeData.노선명)
          })
        );
      });
    }, REACH_DELAY_MS);
    return () => clearTimeout(timer);
  }, [current, routeData, stopSeq, reachValue, reachValid]);

  const loadSegment = () =>
    run(async () => {
      setSegment(
        await fetchRouteSegment({
          route: routeData.노선명,
          dayType,
          basis,
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
    callbacks.current.onReach?.(reachSeq);
  }, [callbacks, reachSeq]);
  useEffect(() => {
    callbacks.current.onSegment?.(segment ? [segment.fromSeq, segment.toSeq] : null);
  }, [callbacks, segment]);
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

      <BasisControls basis={basis} onChange={setBasis} dayType={dayType} showFixed />

      {error &&
        (isPeriodUnavailable(error) && !isPlain(basis) ? (
          <PeriodUnavailable message={error} basis={basis} onTypical={() => setBasis({ ...TYPICAL, fixed: basis.fixed })} />
        ) : (
          <div className="error-box" role="alert">{error}</div>
        ))}

      {movedNote && routeData && <p className="note">{movedNote}</p>}

      {!routeData && !error && <p className="note">{t("route.empty")}</p>}

      {routeData && (
        <>
          <p className="route-summary num">
            {hours
              ? t("route.summaryHour", {
                  total: fmt(routeData.totalPassengers),
                  stops: routeData.stops.length,
                  hour: t("route.hour.range", { from: pad2(hours.from), to: pad2(hours.to + 1) })
                })
              : basis.period?.day
                ? t("route.summaryDay", {
                    total: fmt(routeData.totalPassengers),
                    stops: routeData.stops.length,
                    period: basis.period.day
                  })
                : basis.period
                ? t("route.summaryPeriod", {
                    total: fmt(routeData.totalPassengers),
                    stops: routeData.stops.length,
                    period: periodParam(basis.period).replace("..", "–"),
                    days: routeData.nDays ?? "-"
                  })
                : t("route.summary", { total: fmt(routeData.totalPassengers), stops: routeData.stops.length, days: routeData.nDays ?? "-" })}
            {routeData.unorderedPassengers > 0 && (
              <span className="note"> · {t("route.unordered", { value: fmt(routeData.unorderedPassengers) })}</span>
            )}
          </p>

          <div className="route-tools">
            <button type="button" className="chip" onClick={copyLink}>
              {copied ? t("route.link.copied") : t("route.link.copy")}
            </button>
            {!compare && range && (
              <button
                type="button"
                className="chip"
                onClick={() => setCompare({ period: { from: range.last, to: range.last }, uncertain: false })}
              >
                {t("route.compare.open")}
              </button>
            )}
          </div>

          <FleetInputs routeData={routeData} hours={basis.hours} fleet={fleet} setFleet={setFleet} fmt={fmt} />

          <RouteHistory route={routeData.노선명} onCompare={onCompare} />
          {compareNote && <p className="note">{compareNote}</p>}
          {compare && (
            <RouteCompare
              route={routeData.노선명}
              dayType={dayType}
              basis={basis}
              current={routeData}
              compare={compare}
              setCompare={setCompare}
              range={range}
            />
          )}

          <div className="chart-block">
            <h2 className="section-title">{t("route.chart.title")}</h2>
            <div className="legend">
              <span><i className="swatch swatch--up" />{t("route.chart.up")}</span>
              {turnaround != null && <span><i className="swatch swatch--down" />{t("route.chart.down")}</span>}
              <span className="note num">{t("route.chart.max", { value: fmt(chart.ownMax) })}</span>
            </div>
            {dayType !== "weekday" && weekdayMax > 0 && (
              <label className="check">
                <input type="checkbox" checked={weekdayScale} onChange={(e) => setWeekdayScale(e.target.checked)} />
                {t("route.chart.weekdayScale", { value: fmt(weekdayMax) })}
                <span className="note num">
                  {" · "}
                  {t("route.chart.weekdayShare", { value: Math.round(((chart.ownMax || 0) / weekdayMax) * 100) })}
                </span>
              </label>
            )}
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
              {chart.scaleY != null && (
                <g>
                  <line x1={40} x2={CHART.width - 12} y1={chart.scaleY} y2={chart.scaleY} stroke="var(--muted)" strokeDasharray="2 3" />
                  <text x={CHART.width - 12} y={chart.scaleY - 3} fontSize="10" textAnchor="end" className="axis-label">
                    {t("route.chart.weekdayLine")}
                  </text>
                </g>
              )}
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
            {segment && (
              <SegmentDayCompare route={routeData.노선명} dayType={dayType} basis={basis} segment={segment} fmt={fmt} />
            )}
          </div>

          <details className="card">
            <summary>{t("route.table.summary", { count: routeData.stops.length })}</summary>
            <CsvButton
              result={routeData}
              toCsv={routeStopsCsv}
              fileName={csvFileName("route", routeData.노선명, "stops", routeData.기준일자, basisFileTag(basis))}
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

const FLOW_TOP = 10;
const FLOW_SORTS = ["riders", "seq", "name"];

/**
 * Where riders go or come from. The response carries every stop: the list
 * shows the busiest ten first, and can be sorted by stop number or name and
 * shown in full.
 */
function FlowList({ title, flows, fmt }) {
  const { t, lang } = useI18n();
  const [by, setBy] = useState("riders");
  const [all, setAll] = useState(false);
  const sorted = useMemo(() => sortFlows(flows, by, lang), [flows, by, lang]);
  // The top ten by riders, then shown in the chosen order.
  const top = useMemo(() => {
    if (all || sorted.length <= FLOW_TOP) return sorted;
    const keep = new Set(sortFlows(flows, "riders").slice(0, FLOW_TOP).map((f) => f.seq));
    return sorted.filter((f) => keep.has(f.seq));
  }, [all, sorted, flows]);
  return (
    <div>
      <h4 className="flow-title">{title}</h4>
      {flows.length === 0 ? (
        <p className="note">{t("route.stop.none")}</p>
      ) : (
        <>
          <span className="chip-row flow-sort" role="group" aria-label={t("route.flow.sortLabel")}>
            {FLOW_SORTS.map((key) => (
              <button key={key} type="button" className="chip" aria-pressed={by === key} onClick={() => setBy(key)}>
                {t(`route.flow.${key}`)}
              </button>
            ))}
          </span>
          <ol className="flow-list">
            {top.map((f) => (
              <li key={f.seq}>
                <span>{f.seq} {f.name}</span>
                <span className="num">{fmt(f.passengers)}</span>
              </li>
            ))}
          </ol>
          {flows.length > FLOW_TOP && (
            <button type="button" className="chip" onClick={() => setAll(!all)}>
              {all ? t("route.flow.showTop", { count: FLOW_TOP }) : t("route.flow.showAll", { count: flows.length })}
            </button>
          )}
        </>
      )}
    </div>
  );
}
