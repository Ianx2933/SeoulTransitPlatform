/**
 * Geometry for the route load chart: riders on board after each stop as an
 * area, with boardings above and alightings below the axis as thin bars.
 *
 * Kept free of React so it can be unit-tested.
 */

/** Scales stops to x positions and loads to y positions inside a box. */
export function buildLoadChart(stops, { width, height, padding = 24 }) {
  if (!Array.isArray(stops) || stops.length === 0) {
    return { points: [], areaPath: "", maxLoad: 0, xOf: () => padding };
  }
  const innerWidth = Math.max(width - padding * 2, 1);
  const innerHeight = Math.max(height - padding * 2, 1);
  const maxLoad = Math.max(...stops.map((s) => s.onBoardAfter || 0), 1);
  const step = stops.length > 1 ? innerWidth / (stops.length - 1) : 0;
  const xOf = (index) => padding + index * step;
  const yOf = (value) => padding + innerHeight - (value / maxLoad) * innerHeight;

  const points = stops.map((stop, index) => ({
    seq: stop.seq,
    x: round(xOf(index)),
    y: round(yOf(stop.onBoardAfter || 0)),
    stop
  }));
  const baseY = round(padding + innerHeight);
  const line = points.map((p, i) => `${i === 0 ? "M" : "L"}${p.x},${p.y}`).join(" ");
  const areaPath = `${line} L${points[points.length - 1].x},${baseY} L${points[0].x},${baseY} Z`;
  return { points, areaPath, maxLoad, baseY, xOf };
}

/**
 * Bars for the riders on board after each stop, one bar per stop in route
 * order, with room below the axis for stop-number labels.
 *
 * labelEvery: label every n-th stop so labels do not overlap (the first, the
 * last and the turnaround are labelled regardless, by the caller).
 */
export function buildBarChart(stops, { width, height, padLeft = 40, padRight = 12, padTop = 28, padBottom = 40, minLabelGap = 22 }) {
  const count = Array.isArray(stops) ? stops.length : 0;
  const innerWidth = Math.max(width - padLeft - padRight, 1);
  const innerHeight = Math.max(height - padTop - padBottom, 1);
  const baseY = padTop + innerHeight;
  if (count === 0) {
    return { bars: [], maxLoad: 0, baseY, labelEvery: 1, slot: 0 };
  }
  const maxLoad = Math.max(...stops.map((s) => s.onBoardAfter || 0), 1);
  const slot = innerWidth / count;
  const barWidth = Math.max(slot * 0.75, 1);
  const bars = stops.map((stop, index) => {
    const h = ((stop.onBoardAfter || 0) / maxLoad) * innerHeight;
    return {
      seq: stop.seq,
      x: round(padLeft + index * slot + (slot - barWidth) / 2),
      cx: round(padLeft + index * slot + slot / 2),
      y: round(baseY - h),
      w: round(barWidth),
      h: round(h),
      stop
    };
  });
  const labelEvery = Math.max(1, Math.ceil(minLabelGap / slot));
  return { bars, maxLoad, baseY: round(baseY), labelEvery, slot };
}

/**
 * Estimated turnaround of a route that goes out and comes back: stops served
 * in both directions usually share a name, so for every name that occurs
 * twice the midpoint of its two sequences points at the turnaround. The
 * median midpoint is taken, snapped to the nearest stop. Returns null when
 * fewer than three names repeat (a one-way or circular route).
 */
export function findTurnaround(stops) {
  const firstSeq = new Map();
  const midpoints = [];
  for (const stop of stops || []) {
    const key = normaliseName(stop.name);
    if (!key) continue;
    if (firstSeq.has(key)) {
      midpoints.push((firstSeq.get(key) + stop.seq) / 2);
    } else {
      firstSeq.set(key, stop.seq);
    }
  }
  if (midpoints.length < 3) return null;
  midpoints.sort((a, b) => a - b);
  const median = midpoints[Math.floor(midpoints.length / 2)];
  let best = null;
  for (const stop of stops) {
    if (best === null || Math.abs(stop.seq - median) < Math.abs(best.seq - median)) {
      best = stop;
    }
  }
  return best ? best.seq : null;
}

function normaliseName(name) {
  return String(name || "").replace(/\s+/g, "").trim();
}

/** The stops between two sequences, inclusive, in route order. */
export function stopsBetween(stops, fromSeq, toSeq) {
  const lo = Math.min(fromSeq, toSeq);
  const hi = Math.max(fromSeq, toSeq);
  return (stops || []).filter((s) => s.seq >= lo && s.seq <= hi);
}

/** Share of a part in a total, as a percentage with one decimal, or "-". */
export function percent(part, total) {
  if (!total) return "-";
  return `${((part / total) * 100).toFixed(1)}%`;
}

function round(value) {
  return Math.round(value * 10) / 10;
}
