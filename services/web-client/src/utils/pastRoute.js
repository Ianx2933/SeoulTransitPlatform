/**
 * A route that ran differently in the chosen period (3411 before its change):
 * its OD stops no longer match the stored current path, so the screen redraws
 * the line from the period's stop order instead. Kept free of React for tests.
 */
import { styleSegments } from "./routeLoad.js";

/** Below this share of OD stops on the current path, the period's route is drawn instead. */
export const PAST_MATCH_THRESHOLD = 0.8;
/** A partial change (one end rerouted): this many stops off the current path, below the looser share. */
export const PAST_MIN_MISSING = 3;
export const PAST_PARTIAL_THRESHOLD = 0.92;
/** The API takes at most this many stops (StopPathService.MAX_STOPS). */
export const MAX_PATH_STOPS = 240;

const pad5 = (value) => {
  const s = String(value ?? "").trim();
  return /^\d{1,5}$/.test(s) ? s.padStart(5, "0") : null;
};

const ordered = (stops) => [...(stops || [])].sort((a, b) => a.seq - b.seq);

/**
 * How many of the period's OD stops the current stored path passes.
 * Null when the stored path lists no stops (nothing to compare with).
 */
export function stopMatch(stops, roadPath) {
  const current = new Set((roadPath?.stops || []).map((s) => pad5(s.ars)).filter(Boolean));
  if (current.size === 0) return null;
  const own = [...new Set((stops || []).map((s) => pad5(s.ars)).filter(Boolean))];
  if (own.length === 0) return null;
  const matched = own.filter((a) => current.has(a)).length;
  return { matched, total: own.length, ratio: matched / own.length };
}

export function isPastRoute(match, threshold = PAST_MATCH_THRESHOLD) {
  if (!match || match.total < 2) return false;
  if (match.ratio < threshold) return true;
  return match.total - match.matched >= PAST_MIN_MISSING && match.ratio < PAST_PARTIAL_THRESHOLD;
}

/** The "stops" parameter: padded ARS in riding order, a stop repeated back to back once. */
export function stopPathQuery(stops) {
  const out = [];
  for (const s of ordered(stops)) {
    const a = pad5(s.ars);
    if (a && out[out.length - 1] !== a) out.push(a);
  }
  if (out.length < 2) return null;
  return out.slice(0, MAX_PATH_STOPS).join(",");
}

/**
 * The day whose route master snapshot to draw: the chosen day, or the last day
 * of the period's last month. Null for the typical day.
 */
export function periodEndDay(period) {
  if (!period) return null;
  if (period.day) return period.day;
  const m = /^(\d{4})-(\d{2})$/.exec(period.to || "");
  if (!m) return null;
  const last = new Date(Date.UTC(Number(m[1]), Number(m[2]), 0)).getUTCDate();
  return `${m[1]}-${m[2]}-${String(last).padStart(2, "0")}`;
}

/** Stop points of a stop path in the shape placeStops reads. */
export function pastPoints(stopPath) {
  return (stopPath?.stops || []).map((s) => ({ nodeId: s.ars, nodeName: s.name, lat: s.lat, lng: s.lng }));
}

/**
 * Segments of the stop path with the riders on board after each segment's
 * first stop, styled like the road view. A stop the OD does not list (the
 * master snapshot has every stop the route served) keeps the riders of the
 * stop before it. Segments with no riders come back unstyled (grey) so the
 * whole line still shows.
 */
export function pastSegments(stops, stopPath) {
  const order = ordered(stops).map((s) => ({ ars: pad5(s.ars), load: Number(s.onBoardAfter) || 0 }));
  let j = 0;
  let load = 0;
  const raw = (stopPath?.segments || []).map((seg, i) => {
    let k = j;
    while (k < order.length && order[k].ars !== seg.fromArs) k += 1;
    if (k < order.length) {
      load = order[k].load;
      j = k + 1;
    }
    return { ...seg, key: `${i}-${seg.fromArs}-${seg.toArs}`, load, routes: [] };
  });
  const styled = styleSegments(raw, { minLoad: 0 });
  const byKey = new Map(styled.segments.map((s) => [s.key, s]));
  return raw.map((s) => byKey.get(s.key) || { ...s, color: "#8a929c", weight: 2.5, level: -1 });
}
