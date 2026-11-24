import { appendBasis } from "../utils/basis.js";
import { fetchJson, fetchMapDemand } from "./mapDemandApi.js";

/**
 * Route analysis (served by RouteAnalysisController).
 *
 * dayType is weekday, saturday or sunday_holiday. basis (utils/basis.js) picks
 * the typical day or a period of months, and boarding hours (any day type:
 * the server splits the day by the boarding stop's hourly profile). reach is
 * a list of rider thresholds for the stop endpoint.
 */
function base(route) {
  return `/api/curated-od/routes/${encodeURIComponent(route)}`;
}

/** Query string for the route analysis endpoints. Kept pure for tests. */
export function routeQuery({ dayType, basis = null, reach = null, from = null, to = null }) {
  const params = new URLSearchParams({ date: dayType });
  appendBasis(params, basis);
  if (Array.isArray(reach) && reach.length > 0) {
    params.set("reach", reach.join(","));
  }
  if (from !== null && from !== undefined) params.set("from", String(from));
  if (to !== null && to !== undefined) params.set("to", String(to));
  return params.toString();
}

/**
 * Every route name the route analysis can serve, as the representative OD names
 * it (5522A난곡, 8773출근, 한강버스01). The map's route list uses the boarding
 * data's names (5522A), which the server also accepts.
 */
export function fetchOdRouteNames() {
  return fetchJson("/api/curated-od/routes", { defaultMessage: "Failed to fetch route names" });
}

export function fetchRouteStops({ route, dayType, basis, messages }) {
  return fetchJson(`${base(route)}/stops?${routeQuery({ dayType, basis })}`, messages);
}

export function fetchRouteStop({ route, dayType, basis, seq, reach, messages }) {
  return fetchJson(`${base(route)}/stops/${seq}?${routeQuery({ dayType, basis, reach })}`, messages);
}

export function fetchRouteSegment({ route, dayType, basis, from, to, messages }) {
  return fetchJson(`${base(route)}/segment?${routeQuery({ dayType, basis, from, to })}`, messages);
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

/** The route's OD grouped by administrative district, with boundaries. */
export function fetchRouteDongOd({ route, dayType, basis, messages }) {
  return fetchJson(`${base(route)}/dong-od?${routeQuery({ dayType, basis })}`, messages);
}

/** Road path of the route ([lat, lng] points) and the stop it turns at, from the city's bus API. */
export function fetchRoutePath({ route }) {
  return fetchJson(`${base(route)}/path`, {
    defaultMessage: "Failed to fetch route path",
    notFoundMessage: "No road path for this route."
  });
}
