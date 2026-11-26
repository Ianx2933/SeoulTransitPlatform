/**
 * Route analysis state in the address: "#/route?r=3411&d=saturday&p=2024-09..2024-11&h=7-9&v=20&t=150"
 * (v, t: vehicles and trips typed in by hand).
 * A link opens the same route, day type, months and hours. Pure, for tests.
 */
import { hoursParam, periodParam, TYPICAL } from "./basis.js";

const DAY_TYPES = ["weekday", "saturday", "sunday_holiday"];

export function routeLinkHash({ route, dayType, basis, fleet }) {
  const params = new URLSearchParams();
  if (route) params.set("r", route);
  if (dayType && dayType !== "weekday") params.set("d", dayType);
  if (basis?.period && !basis.period.day) params.set("p", periodParam(basis.period));
  const h = hoursParam(basis?.hours);
  if (h) params.set("h", h);
  if (fleet?.vehicles) params.set("v", String(fleet.vehicles));
  if (fleet?.trips) params.set("t", String(fleet.trips));
  const q = params.toString();
  return q ? `#/route?${q}` : "#/route";
}

/** The state a "#/route?..." hash names, or null when it names no route. */
export function parseRouteLink(hash) {
  const m = /^#\/route\?(.*)$/.exec(String(hash || ""));
  if (!m) return null;
  const params = new URLSearchParams(m[1]);
  const route = (params.get("r") || "").trim().slice(0, 30);
  if (!route) return null;
  const d = params.get("d");
  const dayType = DAY_TYPES.includes(d) ? d : "weekday";
  let period = null;
  const p = /^(\d{4}-\d{2})\.\.(\d{4}-\d{2})$/.exec(params.get("p") || "");
  if (p && p[1] <= p[2]) period = { from: p[1], to: p[2] };
  let hours = null;
  const h = /^(\d{1,2})(?:-(\d{1,2}))?$/.exec(params.get("h") || "");
  if (h) {
    const from = Number(h[1]);
    const to = h[2] === undefined ? from : Number(h[2]);
    if (from <= to && to <= 23) hours = { from, to };
  }
  const count = (k) => {
    const n = Number(params.get(k));
    return Number.isInteger(n) && n > 0 && n <= 100000 ? n : null;
  };
  return { route, dayType, basis: { ...TYPICAL, period, hours }, fleet: { vehicles: count("v"), trips: count("t") } };
}
