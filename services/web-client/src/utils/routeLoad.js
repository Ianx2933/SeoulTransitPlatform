import { classOf, quantileBreaks } from "./dongColor.js";

/** Ten steps from dark green (few riders) to dark red (many), ColorBrewer RdYlGn reversed. */
export const LOAD_COLORS = [
  "#006837", "#1a9850", "#66bd63", "#a6d96a", "#d9ef8b",
  "#fee08b", "#fdae61", "#f46d43", "#d73027", "#a50026"
];

/** Line weight per step, thin to thick. */
export const LOAD_WEIGHTS = [2.5, 3, 3.5, 4, 5, 6, 7, 8, 9, 10];

export const DEFAULT_MIN_LOAD = 10;

/** Riders of a segment on the chosen routes; all routes when none are chosen. */
export function loadOn(segment, routes) {
  if (!routes || routes.length === 0) return segment.load;
  const byRoute = segment.byRoute || {};
  return routes.reduce((sum, r) => sum + (byRoute[r] || 0), 0);
}

/**
 * Road segments of an area's bus riders, styled for the map. With routes
 * chosen, a segment's load is the riders of those routes only. Segments under
 * minLoad are dropped; the rest are split into ten steps at quantiles of their
 * loads (so every colour is used) and drawn thicker for more riders. Returned
 * low first so the busiest segments are drawn on top. Pure, for tests.
 *
 * @returns {{segments: object[], breaks: number[], shown: number, hidden: number, max: number}}
 */
export function styleSegments(segments, { minLoad = 0, routes = [] } = {}) {
  const drawable = (segments || [])
    .filter((s) => Array.isArray(s.path) && s.path.length >= 2)
    .map((s) => (routes.length ? { ...s, load: loadOn(s, routes), routes: s.routes.filter((r) => routes.includes(r)) } : s))
    .filter((s) => s.load > 0);
  const kept = drawable.filter((s) => s.load >= minLoad);
  const breaks = quantileBreaks(kept.map((s) => s.load), LOAD_COLORS.length);
  const styled = kept
    .map((s) => {
      const k = Math.max(0, classOf(s.load, breaks));
      return { ...s, color: LOAD_COLORS[k], weight: LOAD_WEIGHTS[k], level: k };
    })
    .sort((a, b) => a.load - b.load);
  return {
    segments: styled,
    breaks,
    shown: styled.length,
    hidden: drawable.length - styled.length,
    max: styled.length ? styled[styled.length - 1].load : 0
  };
}

/**
 * Legend steps: colour and the rider range it stands for (from inclusive, to
 * exclusive except the last). Steps with an empty range, which appear when many
 * segments share one load, are left out.
 */
export function legendSteps(styled, minLoad = 0) {
  const { breaks, max, shown } = styled;
  if (!shown) return [];
  const lowest = styled.segments[0].load;
  const steps = [];
  for (let k = 0; k <= breaks.length; k += 1) {
    const from = k === 0 ? Math.max(minLoad, lowest) : breaks[k - 1];
    const to = k === breaks.length ? max : breaks[k];
    if (k < breaks.length && to <= from) continue;
    steps.push({ color: LOAD_COLORS[k], from, to, last: k === breaks.length });
  }
  return steps;
}

/**
 * "302 520 · 440 310 · +3" for a segment's tooltip: its (chosen) routes by
 * riders, most first, the first `limit` named. fmt formats a number.
 */
export function routeBreakdown(segment, fmt = String, limit = 6) {
  const byRoute = segment.byRoute || {};
  const rows = (segment.routes || [])
    .map((r) => [r, byRoute[r]])
    .sort((a, b) => (b[1] ?? 0) - (a[1] ?? 0));
  const named = rows.slice(0, limit).map(([r, v]) => (v == null ? r : `${r} ${fmt(v)}`));
  const more = rows.length - named.length;
  return more > 0 ? `${named.join(" · ")} · +${more}` : named.join(" · ");
}

/** [[south, west], [north, east]] around every segment, or null. */
export function loadBounds(segments) {
  let s = Infinity;
  let w = Infinity;
  let n = -Infinity;
  let e = -Infinity;
  for (const seg of segments || []) {
    for (const [lat, lng] of seg.path || []) {
      if (!Number.isFinite(lat) || !Number.isFinite(lng)) continue;
      s = Math.min(s, lat);
      n = Math.max(n, lat);
      w = Math.min(w, lng);
      e = Math.max(e, lng);
    }
  }
  return Number.isFinite(s) ? [[s, w], [n, e]] : null;
}

/**
 * Routes ranked by riders on their busiest segment (a rider counts on every
 * segment, so sums would mislead). Uses each route's own riders when the
 * segment has them by route.
 */
export function busiestRoutes(segments, limit = Infinity) {
  const best = new Map();
  for (const seg of segments || []) {
    for (const r of seg.routes || []) {
      const load = seg.byRoute && r in seg.byRoute ? seg.byRoute[r] : seg.load;
      best.set(r, Math.max(best.get(r) || 0, load));
    }
  }
  return [...best.entries()].sort((a, b) => b[1] - a[1]).slice(0, limit).map(([route, load]) => ({ route, load }));
}
