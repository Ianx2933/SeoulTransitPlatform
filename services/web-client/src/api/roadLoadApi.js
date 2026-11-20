import { appendBasis, basisKey } from "../utils/basis.js";
import { fetchJson } from "./mapDemandApi.js";

/**
 * Bus riders on the road (RoadLoadController): chosen routes added up, the
 * riders of one stop on every route, or the riders who pass one segment.
 * dayType is weekday, saturday or sunday_holiday; basis (utils/basis.js) adds
 * a period and boarding hours.
 */
export function fetchRoadLoad(request) {
  const params = new URLSearchParams({ date: request.dayType || "weekday" });
  let path;
  if (request.kind === "routes") {
    params.set("routes", request.routes.join(","));
    path = "routes";
  } else if (request.kind === "stop") {
    params.set("ars", request.ars);
    params.set("direction", request.direction || "board");
    path = "stop";
  } else {
    params.set("from", request.from);
    params.set("to", request.to);
    path = "segment";
  }
  appendBasis(params, request.basis);
  return fetchJson(`/api/road-load/${path}?${params}`, {
    defaultMessage: "Failed to fetch bus riders on the road",
    notFoundMessage: "No bus riders for this choice."
  });
}

/** Stable key of a request, for caching and for matching a result to its request. */
export function roadLoadKey(request) {
  if (!request) return "";
  const day = request.dayType || "weekday";
  const b = basisKey(request.basis);
  const tail = b ? `|${b}` : "";
  if (request.kind === "routes") return `routes|${day}|${request.routes.join(",")}${tail}`;
  if (request.kind === "stop") return `stop|${day}|${request.ars}|${request.direction || "board"}${tail}`;
  return `segment|${day}|${request.from}|${request.to}${tail}`;
}
