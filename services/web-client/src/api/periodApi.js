import { periodParam } from "../utils/basis.js";
import { fetchJson } from "./mapDemandApi.js";

/**
 * The period picker's data (PeriodController): the months with route OD, and
 * the days a period averages over. Both are asked once per page load and kept
 * here; a failure (BigQuery off, 503) is kept too, so the picker shows the
 * period and hours as unavailable instead of asking again on every render.
 */
let rangePromise = null;

export function fetchPeriodRange() {
  if (!rangePromise) {
    rangePromise = fetchJson("/api/period/range", { defaultMessage: "Period range unavailable" }).then(
      (r) => (r && r.first && r.last ? r : Promise.reject(new Error("No months with route OD"))),
      (e) => {
        // Let a later page load try again.
        setTimeout(() => {
          rangePromise = null;
        }, 60_000);
        throw e;
      }
    );
  }
  return rangePromise;
}

const daysCache = new Map();

/** { dayType, period, nDays, days: ["2026-03-11", ...] } for a period and day type. */
export function fetchPeriodDays({ period, dayType }) {
  const p = periodParam(period);
  const key = `${p}|${dayType}`;
  if (!daysCache.has(key)) {
    const params = new URLSearchParams({ period: p, date: dayType });
    const promise = fetchJson(`/api/period/days?${params}`, { defaultMessage: "Period days unavailable" });
    promise.catch(() => daysCache.delete(key));
    daysCache.set(key, promise);
  }
  return daysCache.get(key);
}
