import { useMemo, useState } from "react";

import {
  fetchRouteSegment,
  fetchRouteStop,
  fetchRouteStops
} from "../api/routeAnalysisApi.js";
import { buildBarChart, findTurnaround, percent } from "../utils/routeLoadChart.js";

const DAY_TYPES = [
  { value: "weekday", label: "Weekday" },
  { value: "saturday", label: "Saturday" },
  { value: "sunday_holiday", label: "Sunday / holiday" }
];

const CHART = { width: 960, height: 260 };

const fmt = (value) =>
  value === null || value === undefined
    ? "-"
    : Number(value).toLocaleString(undefined, { maximumFractionDigits: 1 });

/**
 * Route analysis on the typical-day OD.
 *
 * Pick a route and a day type to see riders on board along the route and each
 * stop's boardings and alightings. Click a stop for where its riders go and
 * come from and how far its load carries; pick two stops for the demand
 * inside the segment and crossing its ends. Every figure is a mean daily
 * passenger count over the days of that type in the last twelve months.
 */
export default function RouteAnalysisPanel({ busLines = [] }) {
  const [route, setRoute] = useState("");
  const [dayType, setDayType] = useState("weekday");
  const [routeData, setRouteData] = useState(null);
  const [stopDetail, setStopDetail] = useState(null);
  const [segment, setSegment] = useState(null);
  const [fromSeq, setFromSeq] = useState("");
  const [toSeq, setToSeq] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  const chart = useMemo(
    () => buildBarChart(routeData?.stops || [], CHART),
    [routeData]
  );
  const turnaround = useMemo(() => findTurnaround(routeData?.stops), [routeData]);

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

  const loadRoute = (event) => {
    event?.preventDefault();
    const name = route.trim();
    if (!name) {
      setError("Enter a bus route, for example 302 or 452.");
      return;
    }
    run(async () => {
      const data = await fetchRouteStops({ route: name, dayType });
      setRouteData(data);
      setStopDetail(null);
      setSegment(null);
      const seqs = data.stops.map((s) => s.seq);
      setFromSeq(String(seqs[0]));
      setToSeq(String(seqs[Math.min(seqs.length - 1, 10)]));
    });
  };

  const openStop = (seq) =>
    run(async () => {
      setStopDetail(await fetchRouteStop({ route: routeData.노선명, dayType, seq }));
    });

  const loadSegment = () =>
    run(async () => {
      setSegment(
        await fetchRouteSegment({
          route: routeData.노선명,
          dayType,
          from: Number(fromSeq),
          to: Number(toSeq)
        })
      );
    });

  const highlighted = new Set([
    ...(stopDetail ? [stopDetail.seq] : []),
    ...(segment ? segment.stops.map((s) => s.seq) : [])
  ]);

  return (
    <section className="route-analysis-panel" style={panelStyle}>
      <div>
        <strong>Route analysis (typical-day OD)</strong>
        <p style={mutedStyle}>
          Mean daily passengers over the days of the chosen type in the last
          twelve months, from corrected TOPIS route OD. Hourly detail is not
          part of this view.
        </p>
      </div>

      <form onSubmit={loadRoute} style={rowStyle}>
        <label style={labelStyle}>
          Route
          <input
            list="route-analysis-bus-lines"
            value={route}
            onChange={(e) => setRoute(e.target.value)}
            placeholder="302"
            style={inputStyle}
          />
          <datalist id="route-analysis-bus-lines">
            {busLines.slice(0, 2000).map((line) => (
              <option key={line} value={line} />
            ))}
          </datalist>
        </label>
        <label style={labelStyle}>
          Day type
          <select value={dayType} onChange={(e) => setDayType(e.target.value)} style={inputStyle}>
            {DAY_TYPES.map((d) => (
              <option key={d.value} value={d.value}>
                {d.label}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={loading}>
          {loading ? "Loading…" : "Analyse route"}
        </button>
      </form>

      {error && <div className="error-box">{error}</div>}

      {routeData && (
        <>
          <p style={{ margin: 0 }}>
            <strong>{routeData.노선명}</strong> · {fmt(routeData.totalPassengers)} passengers a day ·{" "}
            {routeData.stops.length} stops · mean of {routeData.nDays ?? "-"} days
            {routeData.unorderedPassengers > 0 &&
              ` · ${fmt(routeData.unorderedPassengers)} not placeable on the route`}
          </p>

          <svg
            viewBox={`0 0 ${CHART.width} ${CHART.height}`}
            style={{ width: "100%", height: "auto", background: "#f8fafc", borderRadius: 8 }}
            role="img"
            aria-label="Riders on board after each stop"
          >
            <text x={8} y={18} fontSize="12" fill="#475569">
              On board after each stop (max {fmt(chart.maxLoad)} a day) — click a bar for stop detail
            </text>
            <line x1={40} x2={CHART.width - 12} y1={chart.baseY} y2={chart.baseY} stroke="#94a3b8" />
            {chart.bars.map((b, index) => {
              const afterTurn = turnaround != null && b.seq > turnaround;
              const fill = highlighted.has(b.seq)
                ? "#dc2626"
                : afterTurn
                  ? "#14b8a6"
                  : "#3b82f6";
              const showLabel =
                index % chart.labelEvery === 0 ||
                index === chart.bars.length - 1 ||
                b.seq === turnaround;
              return (
                <g key={b.seq} onClick={() => openStop(b.seq)} style={{ cursor: "pointer" }}>
                  <rect x={b.x} y={b.y} width={b.w} height={Math.max(b.h, 0.5)} fill={fill}>
                    <title>
                      {`${b.seq} ${b.stop.name}: ${fmt(b.stop.onBoardAfter)} on board, ` +
                        `${fmt(b.stop.boardings)} on, ${fmt(b.stop.alightings)} off`}
                    </title>
                  </rect>
                  {showLabel && (
                    <text
                      x={b.cx}
                      y={chart.baseY + 14}
                      fontSize="10"
                      textAnchor="middle"
                      fill={b.seq === turnaround ? "#b45309" : "#475569"}
                      fontWeight={b.seq === turnaround ? 700 : 400}
                    >
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
                    <line
                      x1={b.cx}
                      x2={b.cx}
                      y1={28}
                      y2={chart.baseY + 18}
                      stroke="#b45309"
                      strokeDasharray="4 3"
                    />
                    <text x={b.cx + 4} y={40} fontSize="11" fill="#b45309" fontWeight="700">
                      {`Turnaround (est.) ${b.seq} ${b.stop.name}`}
                    </text>
                  </g>
                ))}
            <text x={CHART.width / 2} y={CHART.height - 6} fontSize="11" textAnchor="middle" fill="#64748b">
              Stop sequence {turnaround != null ? "— blue: outbound, teal: return" : ""}
            </text>
          </svg>

          <div style={rowStyle}>
            <label style={labelStyle}>
              Segment from
              <StopSelect stops={routeData.stops} value={fromSeq} onChange={setFromSeq} />
            </label>
            <label style={labelStyle}>
              to
              <StopSelect stops={routeData.stops} value={toSeq} onChange={setToSeq} />
            </label>
            <button type="button" onClick={loadSegment} disabled={loading}>
              Analyse segment
            </button>
          </div>

          {segment && <SegmentView segment={segment} />}
          {stopDetail && <StopView detail={stopDetail} />}

          <details>
            <summary>Stop table ({routeData.stops.length} stops)</summary>
            <table style={tableStyle}>
              <thead>
                <tr>
                  <th>Seq</th>
                  <th>Stop</th>
                  <th>ARS</th>
                  <th>Boardings</th>
                  <th>Alightings</th>
                  <th>On board after</th>
                </tr>
              </thead>
              <tbody>
                {routeData.stops.map((s) => (
                  <tr
                    key={s.seq}
                    onClick={() => openStop(s.seq)}
                    style={{ cursor: "pointer", background: highlighted.has(s.seq) ? "#fef3c7" : undefined }}
                  >
                    <td>{s.seq}</td>
                    <td>{s.name}</td>
                    <td>{s.ars}</td>
                    <td style={numStyle}>{fmt(s.boardings)}</td>
                    <td style={numStyle}>{fmt(s.alightings)}</td>
                    <td style={numStyle}>{fmt(s.onBoardAfter)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </details>
        </>
      )}
    </section>
  );
}

function StopSelect({ stops, value, onChange }) {
  return (
    <select value={value} onChange={(e) => onChange(e.target.value)} style={inputStyle}>
      {stops.map((s) => (
        <option key={s.seq} value={s.seq}>
          {s.seq} {s.name}
        </option>
      ))}
    </select>
  );
}

function SegmentView({ segment }) {
  const t = segment.routeTotal;
  const rows = [
    ["Within (both ends inside)", segment.within],
    ["Leaving (board inside, alight past the end)", segment.leaving],
    ["Entering (board before the start, alight inside)", segment.entering],
    ["Passing through", segment.through]
  ];
  return (
    <div style={cardStyle}>
      <strong>
        Segment {segment.fromSeq} {segment.fromName} → {segment.toSeq} {segment.toName}
      </strong>
      <table style={tableStyle}>
        <tbody>
          {rows.map(([label, value]) => (
            <tr key={label}>
              <td>{label}</td>
              <td style={numStyle}>{fmt(value)}</td>
              <td style={numStyle}>{percent(value, t)} of route</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={twoColStyle}>
        <FlowList title="Riders leaving go to" flows={segment.leavingTo} />
        <FlowList title="Riders entering come from" flows={segment.enteringFrom} />
      </div>
      <details>
        <summary>Per stop inside the segment</summary>
        <table style={tableStyle}>
          <thead>
            <tr>
              <th>Seq</th>
              <th>Stop</th>
              <th>On</th>
              <th>Off</th>
              <th>Board here, leave past end</th>
              <th>Bound past end, on board</th>
            </tr>
          </thead>
          <tbody>
            {segment.stops.map((s) => (
              <tr key={s.seq}>
                <td>{s.seq}</td>
                <td>{s.name}</td>
                <td style={numStyle}>{fmt(s.boardings)}</td>
                <td style={numStyle}>{fmt(s.alightings)}</td>
                <td style={numStyle}>{fmt(s.leavingHere)}</td>
                <td style={numStyle}>{fmt(s.leavingOnBoard)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </details>
    </div>
  );
}

function StopView({ detail }) {
  return (
    <div style={cardStyle}>
      <strong>
        Stop {detail.seq} {detail.name} ({detail.ars})
      </strong>
      <p style={mutedStyle}>
        {fmt(detail.boardings)} board, {fmt(detail.alightings)} alight,{" "}
        {fmt(detail.onBoardAfter)} on board as the bus leaves.
      </p>
      <table style={tableStyle}>
        <thead>
          <tr>
            <th>More than … of those on board</th>
            <th>stay on board as far as</th>
          </tr>
        </thead>
        <tbody>
          {detail.reach.map((r) => (
            <tr key={r.level}>
              <td>{fmt(r.level)}</td>
              <td>{r.untilSeq == null ? "-" : `${r.untilSeq} ${r.untilName}`}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div style={twoColStyle}>
        <FlowList title="Boarding here, alight at" flows={detail.destinations} />
        <FlowList title="Alighting here, boarded at" flows={detail.origins} />
      </div>
    </div>
  );
}

function FlowList({ title, flows }) {
  return (
    <div>
      <div style={{ fontWeight: 700, marginBottom: 4 }}>{title}</div>
      {flows.length === 0 ? (
        <div style={mutedStyle}>None</div>
      ) : (
        <ol style={{ margin: 0, paddingLeft: 20 }}>
          {flows.map((f) => (
            <li key={f.seq}>
              {f.seq} {f.name} — {fmt(f.passengers)}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

const panelStyle = {
  display: "grid",
  gap: "12px",
  margin: "16px 20px",
  padding: "16px 18px",
  borderRadius: "16px",
  background: "white",
  boxShadow: "0 8px 24px rgba(15, 23, 42, 0.08)"
};
const cardStyle = { display: "grid", gap: "8px", padding: "12px", border: "1px solid #e2e8f0", borderRadius: "10px" };
const rowStyle = { display: "flex", gap: "12px", alignItems: "end", flexWrap: "wrap" };
const labelStyle = { display: "grid", gap: "4px", fontSize: "14px", fontWeight: 700 };
const inputStyle = { height: "34px", padding: "0 8px", border: "1px solid #cbd5e1", borderRadius: "8px", minWidth: "160px" };
const mutedStyle = { margin: 0, color: "#64748b", fontSize: "14px" };
const tableStyle = { width: "100%", borderCollapse: "collapse", fontSize: "13px" };
const numStyle = { textAlign: "right" };
const twoColStyle = { display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(260px, 1fr))", gap: "12px" };
