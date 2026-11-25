/**
 * What OD a view asks for besides the day type: the typical day of the last
 * twelve months or a period of whole months (BigQuery), and the whole day or
 * boarding hours. Pure, for tests.
 *
 *   basis = { period: null | { from: "YYYY-MM", to: "YYYY-MM", day?: "YYYY-MM-DD" },
 *             hours:  null | { from: 0-23, to: 0-23 },   // inclusive
 *             fixed:  true | false }                      // fixed road colours while hours change
 *
 * A period with a day is one TOPIS day (from = to = its month): the day's
 * own figures, not a mean. The API takes period=YYYY-MM[..YYYY-MM] or
 * period=YYYY-MM-DD, and hours=H or H-H (Period, Hours).
 */
export const TYPICAL = Object.freeze({ period: null, hours: null, fixed: true });

export const MAX_MONTHS = 12;

/** The typical day for the whole day: everything from Cloud SQL. */
export function isPlain(basis) {
  return !basis || (!basis.period && !basis.hours);
}

export function periodParam(period) {
  if (!period) return null;
  if (period.day) return period.day;
  return period.from === period.to ? period.from : `${period.from}..${period.to}`;
}

/** The same number of months moved by delta inside the data, or null past either end. */
export function shiftPeriod(period, delta, range) {
  if (!period || period.day) return null;
  const n = monthCount(period.from, period.to);
  const from = addMonths(period.from, delta * n);
  const to = addMonths(period.to, delta * n);
  if ((range?.first && from < range.first) || (range?.last && to > range.last)) return null;
  return { from, to };
}

export function hoursParam(hours) {
  if (!hours) return null;
  return hours.from === hours.to ? String(hours.from) : `${hours.from}-${hours.to}`;
}

/**
 * Adds period and hours to URL parameters. withHours false is for endpoints
 * that only take whole days (district OD, how far, summary).
 */
export function appendBasis(params, basis, { withHours = true } = {}) {
  if (!basis) return params;
  const p = periodParam(basis.period);
  if (p) params.set("period", p);
  const h = withHours ? hoursParam(basis.hours) : null;
  if (h) params.set("hours", h);
  return params;
}

/** Stable key of what a request reads ("" for the plain typical day). */
export function basisKey(basis, { withHours = true } = {}) {
  if (!basis) return "";
  const parts = [];
  const p = periodParam(basis.period);
  if (p) parts.push(`p${p}`);
  const h = withHours ? hoursParam(basis.hours) : null;
  if (h) parts.push(`h${h}`);
  return parts.join("|");
}

/** "2026-03_2026-05_h7-9" for file names, "" for the plain typical day. */
export function basisFileTag(basis, { withHours = true } = {}) {
  return basisKey(basis, { withHours }).replace(/\.\./g, "_").replace(/\|/g, "_").replace(/^p/, "");
}

export function hoursWidth(hours) {
  return hours ? hours.to - hours.from + 1 : 24;
}

/** The same number of hours moved by delta, or null past 0 or 23. */
export function shiftHours(hours, delta) {
  if (!hours) return null;
  const from = hours.from + delta;
  const to = hours.to + delta;
  if (from < 0 || to > 23) return null;
  return { from, to };
}

/** Hours with a new start or end; the other end follows so that from ≤ to. */
export function setHoursEnd(hours, end, value) {
  const h = hours || { from: value, to: value };
  if (end === "from") return { from: value, to: Math.max(value, h.to) };
  return { from: Math.min(value, h.from), to: value };
}

const pad2 = (h) => String(h).padStart(2, "0");

/** "07–10" for 7-9 (riders boarding 07:00 to 09:59), "08–09" for one hour. */
export function hoursRange(hours) {
  return hours ? `${pad2(hours.from)}–${pad2(hours.to + 1)}` : "";
}

/** "YYYY-MM" months from first to last, inclusive. */
export function monthsList(first, last) {
  if (!first || !last || first > last) return [];
  const out = [];
  let [y, m] = first.split("-").map(Number);
  const [ly, lm] = last.split("-").map(Number);
  while (y < ly || (y === ly && m <= lm)) {
    out.push(`${y}-${pad2(m)}`);
    m += 1;
    if (m > 12) {
      m = 1;
      y += 1;
    }
  }
  return out;
}

export function monthCount(from, to) {
  const [fy, fm] = from.split("-").map(Number);
  const [ty, tm] = to.split("-").map(Number);
  return (ty - fy) * 12 + (tm - fm) + 1;
}

/** The month n months after (or before, n < 0) ym. */
export function addMonths(ym, n) {
  const [y, m] = ym.split("-").map(Number);
  const total = y * 12 + (m - 1) + n;
  return `${Math.floor(total / 12)}-${pad2((total % 12) + 1)}`;
}

/**
 * The period to move to for a route the chosen basis misses: a withdrawn or
 * renumbered route (1155) has OD only up to its last month, outside the
 * typical day's last twelve months. Returns the route's last months (up to
 * MAX_MONTHS), or null when the basis already overlaps the route's months
 * (then the failure is something else) or the route has no OD at all.
 */
export function periodForRoute(routeRange, range, basis, months = MAX_MONTHS) {
  if (!routeRange?.first || !routeRange?.last || !range?.last) return null;
  const shown = basis?.period && !basis.period.day
    ? basis.period
    : { from: addMonths(range.last, -(months - 1)), to: range.last };
  const overlaps = shown.from <= routeRange.last && shown.to >= routeRange.first;
  if (overlaps) return null;
  const from = addMonths(routeRange.last, -(months - 1));
  return { from: from < routeRange.first ? routeRange.first : from, to: routeRange.last };
}

/** The last three months of the data, as a first suggestion. */
export function defaultPeriod(range, months = 3) {
  if (!range?.first || !range?.last) return null;
  const from = addMonths(range.last, -(months - 1));
  return { from: from < range.first ? range.first : from, to: range.last };
}

/**
 * A period inside the data with from ≤ to and at most max months: a changed
 * end wins, so the other end moves (changed is "from" or "to").
 */
export function clampPeriod(period, range, changed = "to", max = MAX_MONTHS) {
  let { from, to } = period;
  if (range?.first && from < range.first) from = range.first;
  if (range?.last && to > range.last) to = range.last;
  if (from > to) {
    if (changed === "from") to = from;
    else from = to;
  }
  if (monthCount(from, to) > max) {
    if (changed === "from") to = addMonths(from, max - 1);
    else from = addMonths(to, -(max - 1));
  }
  return { from, to };
}

/** The API answered 503 PERIOD_UNAVAILABLE (fetchJson keeps 5xx bodies out of the message). */
export function isPeriodUnavailable(message) {
  const text = String(message || "");
  return text.includes("PERIOD_UNAVAILABLE") || /:\s*503\b/.test(text);
}

/**
 * Top of the fixed road scale: the busiest segment of the busiest single hour
 * (legendMax) times the number of hours shown, an upper bound for any window
 * of that width, so moving the hours with ◀ ▶ keeps the colours comparable.
 * null when the scale is not fixed.
 */
export function fixedScaleMax(result, basis) {
  if (!basis?.hours || !basis.fixed || !(result?.legendMax > 0)) return null;
  return result.legendMax * hoursWidth(basis.hours);
}

/**
 * Upper bounds of classes - 1 classes on a fixed scale, spaced evenly on a log
 * scale from low to max (riders on road segments are very skewed).
 */
export function fixedBreaks(max, classes = 10, low = 1) {
  if (!(max > 0)) return [];
  const lo = Math.max(1, low, max / 1000);
  if (lo >= max) return Array.from({ length: classes - 1 }, () => max);
  const ratio = Math.log(max / lo);
  const breaks = [];
  for (let k = 1; k < classes; k += 1) {
    breaks.push(Math.round(lo * Math.exp((ratio * k) / classes) * 10) / 10);
  }
  return breaks;
}
