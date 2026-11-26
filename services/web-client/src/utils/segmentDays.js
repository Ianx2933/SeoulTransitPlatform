/**
 * One route segment on the three day types side by side: the riders who use
 * it (within, leaving, entering, through and their sum) and the route's day
 * total, with Saturday and Sunday as a share of the weekday. Pure, for tests.
 */
export const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];
const PARTS = ["within", "leaving", "entering", "through"];

/** The riders on a segment response: every rider who rides any part of it. */
export function segmentTotal(seg) {
  if (!seg) return null;
  return PARTS.reduce((sum, k) => sum + (Number(seg[k]) || 0), 0);
}

/**
 * A day type's segment counts only when its ends are the same stops as the
 * reference (stop numbers can differ between day types when a stop is skipped).
 */
export function sameEnds(seg, reference) {
  return Boolean(seg && reference && seg.fromName === reference.fromName && seg.toName === reference.toName);
}

/** Rows: key, values per day type (null when missing), share of the weekday per weekend day type. */
export function segmentDayRows(byDay) {
  const value = (seg, key) => {
    if (!seg) return null;
    if (key === "total") return segmentTotal(seg);
    const v = seg[key];
    return v === null || v === undefined ? null : Number(v);
  };
  return ["total", ...PARTS, "routeTotal"].map((key) => {
    const values = Object.fromEntries(DAY_TYPES.map((d) => [d, value(byDay[d], key)]));
    const base = values.weekday;
    const shares = Object.fromEntries(
      DAY_TYPES.filter((d) => d !== "weekday").map((d) => [
        d,
        base > 0 && values[d] !== null ? Math.round((values[d] / base) * 100) : null
      ])
    );
    return { key, values, shares };
  });
}
