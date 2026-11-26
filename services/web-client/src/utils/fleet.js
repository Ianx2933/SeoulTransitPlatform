/**
 * Figures from a route's riders and its vehicles and trips, typed in by hand
 * (no fleet data is loaded yet). Pure, for tests.
 */

/** A positive whole number from an input, or null. */
export function fleetNumber(value) {
  const n = Number(String(value ?? "").replace(/,/g, "").trim());
  return Number.isFinite(n) && n > 0 && n <= 100000 ? Math.round(n) : null;
}

/**
 * total: riders a day (or in the chosen hours); maxOnBoard: riders on the
 * busiest section; vehicles, trips: typed in. Each figure is null when its
 * input is missing.
 */
export function fleetMetrics({ total, maxOnBoard, vehicles, trips }) {
  const div = (a, b) => (b && Number.isFinite(a) ? a / b : null);
  return {
    perTrip: div(total, trips),
    maxPerTrip: div(maxOnBoard, trips),
    perVehicle: div(total, vehicles),
    tripsPerVehicle: div(trips, vehicles)
  };
}
