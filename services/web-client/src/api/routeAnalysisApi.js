import { fetchJson } from "./mapDemandApi.js";

/**
 * Route analysis on the typical-day OD (served by RouteAnalysisController).
 * dayType is weekday, saturday or sunday_holiday.
 */
function base(route) {
  return `/api/curated-od/routes/${encodeURIComponent(route)}`;
}

export function fetchRouteStops({ route, dayType }) {
  const params = new URLSearchParams({ date: dayType });
  return fetchJson(`${base(route)}/stops?${params}`, {
    defaultMessage: "Failed to fetch route stops",
    notFoundMessage: `No OD for route ${route} on this day type.`
  });
}

export function fetchRouteStop({ route, dayType, seq }) {
  const params = new URLSearchParams({ date: dayType });
  return fetchJson(`${base(route)}/stops/${seq}?${params}`, {
    defaultMessage: "Failed to fetch stop detail",
    notFoundMessage: `No OD for route ${route} on this day type.`
  });
}

export function fetchRouteSegment({ route, dayType, from, to }) {
  const params = new URLSearchParams({ date: dayType, from: String(from), to: String(to) });
  return fetchJson(`${base(route)}/segment?${params}`, {
    defaultMessage: "Failed to fetch segment",
    notFoundMessage: `No OD for route ${route} on this day type.`
  });
}
