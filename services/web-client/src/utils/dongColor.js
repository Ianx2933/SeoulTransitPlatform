/**
 * District demand colours: low green to high red, in five classes split at
 * quantiles so the 426 districts spread over all colours. Pure, for tests.
 */
export const DEMAND_COLORS = ["#1a9850", "#91cf60", "#fee08b", "#fc8d59", "#d73027"];

/** Upper bounds of the first four classes (quantiles of the positive values). */
export function quantileBreaks(values, classes = DEMAND_COLORS.length) {
  const sorted = [...values].filter((v) => Number.isFinite(v) && v > 0).sort((a, b) => a - b);
  if (sorted.length === 0) return [];
  const breaks = [];
  for (let k = 1; k < classes; k += 1) {
    breaks.push(sorted[Math.min(sorted.length - 1, Math.floor((k * sorted.length) / classes))]);
  }
  return breaks;
}

/** Class index 0..4 for a value, or -1 for no demand. */
export function classOf(value, breaks) {
  if (!Number.isFinite(value) || value <= 0 || breaks.length === 0) return -1;
  let k = 0;
  while (k < breaks.length && value >= breaks[k]) k += 1;
  return k;
}

export function colorOf(value, breaks) {
  const k = classOf(value, breaks);
  return k < 0 ? null : DEMAND_COLORS[k];
}

/** Map demand day type for district OD from the map filter's day types. */
export function dongDayType(dayTypes) {
  const days = dayTypes || [];
  if (days.length > 0 && days.every((d) => d === "sat")) return "saturday";
  if (days.length > 0 && days.every((d) => d === "sun_holiday")) return "sunday_holiday";
  return "weekday";
}
