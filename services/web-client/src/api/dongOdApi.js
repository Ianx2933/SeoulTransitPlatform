import { fetchJson } from "./mapDemandApi.js";

/**
 * District (행정동) OD on the typical day, all modes (DongOdController).
 * dayType is weekday, saturday or sunday_holiday.
 */
export function fetchDongOdSummary({ dayType }) {
  const params = new URLSearchParams({ date: dayType });
  return fetchJson(`/api/dong-od/summary?${params}`, { defaultMessage: "Failed to fetch district OD" });
}

export function fetchDongOd({ code, dayType, direction = "from", limit = 15 }) {
  const params = new URLSearchParams({ date: dayType, direction, limit: String(limit) });
  return fetchJson(`/api/dong-od/${encodeURIComponent(code)}?${params}`, {
    defaultMessage: "Failed to fetch district OD",
    notFoundMessage: "No district OD for this district."
  });
}

export function fetchDongRoutes({ code, dayType }) {
  const params = new URLSearchParams({ date: dayType });
  return fetchJson(`/api/dong-od/${encodeURIComponent(code)}/routes?${params}`, {
    defaultMessage: "Failed to fetch routes in the district"
  });
}
