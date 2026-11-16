/**
 * District views of one route's district OD (from /dong-od). Pure, for tests.
 */

/** Riders from the chosen district by alighting district, or to it by boarding district. */
export function flowsFor(flows, code, direction) {
  const out = new Map();
  for (const f of flows || []) {
    if (direction === "from" && f.fromCode === code) out.set(f.toCode, (out.get(f.toCode) || 0) + f.passengers);
    if (direction === "to" && f.toCode === code) out.set(f.fromCode, (out.get(f.fromCode) || 0) + f.passengers);
  }
  return out;
}

/** The value shown for each district: its flow with the chosen one, or boardings plus alightings. */
export function shadeValues(data, selected, direction) {
  if (!data) return new Map();
  if (selected) return flowsFor(data.flows, selected, direction);
  return new Map(data.districts.map((d) => [d.code, (d.boardings || 0) + (d.alightings || 0)]));
}

/** Fill opacity from 0.08 (none) to 0.75 (largest), on a square-root scale. */
export function shade(value, max) {
  if (!value || !max) return 0.08;
  return 0.08 + 0.67 * Math.sqrt(value / max);
}

/** Top rows [{ code, name, passengers, share }] for the list next to the map. */
export function topRows(values, districts, limit = 8) {
  const names = new Map((districts || []).map((d) => [d.code, d.name]));
  const total = [...values.values()].reduce((a, b) => a + b, 0);
  return [...values.entries()]
    .sort((a, b) => b[1] - a[1])
    .slice(0, limit)
    .map(([code, passengers]) => ({
      code,
      name: names.get(code) || code,
      passengers,
      share: total > 0 ? passengers / total : 0
    }));
}
