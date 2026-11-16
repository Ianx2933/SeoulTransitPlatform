import { fetchJson, fetchMapDemand } from "./mapDemandApi.js";

/**
 * Route analysis on the typical-day OD (served by RouteAnalysisController).
 *
 * dayType is weekday, saturday or sunday_holiday. hour (0-23) limits the view
 * to riders who boarded in that hour; the server has hourly OD for weekdays
 * only, so the hour is dropped for other day types. reach is a list of rider
 * thresholds for the stop endpoint.
 */
function base(route) {
  return `/api/curated-od/routes/${encodeURIComponent(route)}`;
}

/** Query string for the route analysis endpoints. Kept pure for tests. */
export function routeQuery({ dayType, hour = null, reach = null, from = null, to = null }) {
  const params = new URLSearchParams({ date: dayType });
  if (hour !== null && hour !== undefined && dayType === "weekday") {
    params.set("hour", String(hour));
  }
  if (Array.isArray(reach) && reach.length > 0) {
    params.set("reach", reach.join(","));
  }
  if (from !== null && from !== undefined) params.set("from", String(from));
  if (to !== null && to !== undefined) params.set("to", String(to));
  return params.toString();
}

export function fetchRouteStops({ route, dayType, hour, messages }) {
  return fetchJson(`${base(route)}/stops?${routeQuery({ dayType, hour })}`, messages);
}

export function fetchRouteStop({ route, dayType, hour, seq, reach, messages }) {
  return fetchJson(`${base(route)}/stops/${seq}?${routeQuery({ dayType, hour, reach })}`, messages);
}

export function fetchRouteSegment({ route, dayType, hour, from, to, messages }) {
  return fetchJson(`${base(route)}/segment?${routeQuery({ dayType, hour, from, to })}`, messages);
}

const ALL_HOURS = Array.from({ length: 24 }, (_, h) => h);

/**
 * Stop coordinates of one bus route, from the map demand API (one point per
 * stop, all hours of a Monday summed). Only the coordinates are used.
 */
export function fetchRouteStopPoints({ route }) {
  return fetchMapDemand({
    mode: "bus",
    lines: [route],
    dayTypes: ["mon"],
    dayAggregation: "average",
    hours: ALL_HOURS
  });
}
