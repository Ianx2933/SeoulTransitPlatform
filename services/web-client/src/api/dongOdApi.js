import { fetchJson } from "./mapDemandApi.js";

/**
 * District OD on the typical day by rail and bus (DongOdController), for a
 * 행정동 (level dong, 8-digit code), a 구 (level gu, 5 digits) or Seoul
 * (level city, code 11). dayType is weekday, saturday or sunday_holiday.
 */
export function fetchDongOdSummary({ dayType, level = "dong" }) {
  const params = new URLSearchParams({ date: dayType, level });
  return fetchJson(`/api/dong-od/summary?${params}`, { defaultMessage: "Failed to fetch district OD" });
}

export function fetchDongOd({ code, level = "dong", dest, dayType, direction = "from", limit = 15 }) {
  const params = new URLSearchParams({ date: dayType, level, direction, limit: String(limit) });
  if (dest) params.set("dest", dest);
  return fetchJson(`/api/dong-od/${encodeURIComponent(code)}?${params}`, {
    defaultMessage: "Failed to fetch district OD",
    notFoundMessage: "No district OD for this area."
  });
}

export function fetchDongRoutes({ code, level = "dong", dayType }) {
  const params = new URLSearchParams({ date: dayType, level });
  return fetchJson(`/api/dong-od/${encodeURIComponent(code)}/routes?${params}`, {
    defaultMessage: "Failed to fetch routes in the area"
  });
}

/** Area code for a level, from a 행정동 code: the 행정동, its 구 (5 digits) or Seoul. */
export function areaCode(dongCode, level) {
  if (level === "city") return "11";
  if (level === "gu") return String(dongCode || "").slice(0, 5);
  return dongCode;
}
