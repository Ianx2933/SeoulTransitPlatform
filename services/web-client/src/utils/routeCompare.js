/**
 * Before and after a route change: the two periods to compare, and the stops
 * of both side by side. Pure, for tests.
 */
import { addMonths } from "./basis.js";

const month = (date) => String(date || "").slice(0, 7);
const ars = (s) => {
  const a = String(s?.ars ?? "").trim();
  return /^\d{1,4}$/.test(a) ? a.padStart(5, "0") : a || `name:${s?.name ?? ""}`;
};

/**
 * Periods on either side of a history entry: up to three months ending at the
 * snapshot before the change (the old stops were still there), and up to three
 * months from the snapshot that shows the change, inside the OD's months. A
 * change only seen against today's path, or a snapshot newer than the OD,
 * compares with the OD's last three months; those may fall before the change
 * (it happened somewhere between the two snapshots), so the result says
 * uncertain. Null when the old side has no OD months.
 */
export function comparePeriods(entry, range, months = 3) {
  if (!entry?.since || !range?.first || !range?.last) return null;
  const clampTo = (m) => (m < range.first ? range.first : m > range.last ? range.last : m);
  const beforeTo = month(entry.since);
  if (beforeTo < range.first) return null;
  const before = { from: clampTo(addMonths(beforeTo, -(months - 1))), to: clampTo(beforeTo) };
  let afterFrom = entry.date ? month(entry.date) : null;
  let uncertain = false;
  if (!afterFrom || afterFrom > range.last) {
    afterFrom = addMonths(range.last, -(months - 1));
    uncertain = true;
  }
  if (afterFrom <= before.to) afterFrom = addMonths(before.to, 1);
  if (afterFrom > range.last) return null;
  const after = { from: clampTo(afterFrom), to: clampTo(addMonths(afterFrom, months - 1)) };
  return { before, after, uncertain };
}

/**
 * Stops of two loads of the same route: the second's order, then the stops
 * only the first had. Each row: name, boardings and on-board after in a and
 * b (null where the stop is missing), and the change in boardings.
 */
export function compareStops(aStops, bStops) {
  const order = (stops) => [...(stops || [])].sort((x, y) => x.seq - y.seq);
  const a = new Map();
  for (const s of order(aStops)) {
    const k = ars(s);
    const prev = a.get(k);
    a.set(k, prev ? { ...prev, boardings: prev.boardings + (s.boardings || 0) } : { ...s, boardings: s.boardings || 0 });
  }
  const rows = [];
  const seen = new Set();
  for (const s of order(bStops)) {
    const k = ars(s);
    if (seen.has(k)) continue;
    seen.add(k);
    const before = a.get(k) || null;
    const bBoard = order(bStops).filter((x) => ars(x) === k).reduce((t, x) => t + (x.boardings || 0), 0);
    rows.push({
      key: k,
      name: s.name,
      a: before ? before.boardings : null,
      b: bBoard,
      change: before ? bBoard - before.boardings : null,
      status: before ? "both" : "added"
    });
  }
  for (const [k, s] of a) {
    if (seen.has(k)) continue;
    rows.push({ key: k, name: s.name, a: s.boardings, b: null, change: null, status: "removed" });
  }
  return rows;
}

/** Day totals and the busiest on-board count of a route load. */
export function loadSummary(data) {
  const stops = data?.stops || [];
  return {
    total: Number(data?.totalPassengers) || 0,
    maxOnBoard: stops.reduce((m, s) => Math.max(m, Number(s.onBoardAfter) || 0), 0),
    stops: new Set(stops.map((s) => ars(s))).size
  };
}
