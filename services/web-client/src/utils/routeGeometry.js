/**
 * Places route-analysis stops on the map using the coordinates the map demand
 * API returns for the same route. Kept free of React for tests.
 *
 * Stops are matched by ARS number first (the API's nodeId, compared without
 * leading zeros). A stop left over is matched by name; when the name occurs
 * more than once (both directions), the point nearest the previous placed
 * stop is taken. Stops with no match are left out of the line.
 *
 * An ARS number is not unique across Seoul and Gyeonggi: a 성남 stop on a
 * Seoul route can carry the number of a 중랑구 stop (07832). So an ARS match
 * whose name differs is dropped for a same-name point when there is one, and
 * dropped outright when it lies far from the stop before it. Callers list the
 * route's own points (its stored path, the route master) first: those win.
 */

const normaliseId = (value) => String(value ?? "").trim().replace(/^0+(?=\d)/, "");
const normaliseName = (value) => String(value ?? "").replace(/\s+/g, "");
/** About 10 km, squared in degrees: farther than any two neighbouring stops. */
const FAR2 = 0.09 * 0.09;

function distance2(a, b) {
  const dLat = a.lat - b.lat;
  const dLng = (a.lng - b.lng) * Math.cos((a.lat * Math.PI) / 180);
  return dLat * dLat + dLng * dLng;
}

/** A coordinate given as a number or numeric text; null and "" are missing (Number(null) is 0). */
function isCoordinate(value) {
  if (value === null || value === undefined || value === "") return false;
  return Number.isFinite(Number(value));
}

function validPoint(p) {
  return isCoordinate(p?.lat) && isCoordinate(p?.lng);
}

/** Stops with coordinates, in route order: [{ seq, name, ars, lat, lng }]. */
/**
 * Stops of a stored route path (RoutePathResponse.stops) in the shape
 * placeStops reads, so stops the demand layer lacks (Han River boat piers)
 * can still be placed. Put them first: the route's own positions win over
 * the demand layer's, which an ARS shared with another stop can mislead.
 */
export function pathStopPoints(roadPath) {
  return (roadPath?.stops || []).map((s) => ({ nodeId: s.ars, nodeName: s.name, lat: s.lat, lng: s.lng }));
}

export function placeStops(stops, points) {
  const usable = (points || []).filter(validPoint).map((p) => ({ ...p, lat: Number(p.lat), lng: Number(p.lng) }));
  const byId = new Map();
  const byName = new Map();
  for (const p of usable) {
    const id = normaliseId(p.nodeId);
    if (id && !byId.has(id)) byId.set(id, p);
    const name = normaliseName(p.nodeName);
    if (name) byName.set(name, [...(byName.get(name) || []), p]);
  }

  const ordered = [...(stops || [])].sort((a, b) => a.seq - b.seq);
  const placed = [];
  let previous = null;
  for (const stop of ordered) {
    let point = byId.get(normaliseId(stop.ars));
    if (point && point.nodeName && normaliseName(point.nodeName) !== normaliseName(stop.name)) {
      const named = byName.get(normaliseName(stop.name)) || [];
      if (named.length > 0) point = null;
      else if (previous && distance2(point, previous) > FAR2) point = null;
    }
    if (!point) {
      const candidates = byName.get(normaliseName(stop.name)) || [];
      if (candidates.length === 1 || (candidates.length > 1 && !previous)) {
        point = candidates[0];
      } else if (candidates.length > 1) {
        point = candidates.reduce((best, p) => (distance2(p, previous) < distance2(best, previous) ? p : best));
      }
    }
    if (point) {
      const entry = { seq: stop.seq, name: stop.name, ars: stop.ars, lat: point.lat, lng: point.lng };
      placed.push(entry);
      previous = entry;
    }
  }
  return placed;
}

/**
 * Splits placed stops at the turnaround into the upbound and downbound lines.
 * The turnaround stop ends the first line and starts the second, so the line
 * is drawn without a gap. With no turnaround everything is upbound.
 */
export function splitAtTurnaround(placed, turnaround) {
  const toLatLng = (p) => [p.lat, p.lng];
  if (turnaround == null) return { up: placed.map(toLatLng), down: [] };
  const up = placed.filter((p) => p.seq <= turnaround);
  const down = placed.filter((p) => p.seq >= turnaround);
  if (up.length && down.length && up[up.length - 1].seq !== down[0].seq) {
    down.unshift(up[up.length - 1]);
  }
  return { up: up.map(toLatLng), down: down.map(toLatLng) };
}

/** Line through the placed stops between two sequences, inclusive. */
export function pathBetween(placed, fromSeq, toSeq) {
  if (fromSeq == null || toSeq == null) return [];
  const lo = Math.min(fromSeq, toSeq);
  const hi = Math.max(fromSeq, toSeq);
  return placed.filter((p) => p.seq >= lo && p.seq <= hi).map((p) => [p.lat, p.lng]);
}

/** [[south, west], [north, east]] around the placed stops, or null. */
export function boundsOf(placed) {
  if (!placed.length) return null;
  const lats = placed.map((p) => p.lat);
  const lngs = placed.map((p) => p.lng);
  return [
    [Math.min(...lats), Math.min(...lngs)],
    [Math.max(...lats), Math.max(...lngs)]
  ];
}

/**
 * Sequence of the stop with the given ARS number (leading zeros ignored).
 * A route that serves the stop twice takes the occurrence nearest `near`
 * (the estimated turnaround), or the first. Null when no stop matches.
 */
export function seqOfArs(stops, ars, near = null) {
  const want = normaliseId(ars);
  if (!want) return null;
  const hits = (stops || []).filter((s) => normaliseId(s.ars) === want).map((s) => s.seq);
  if (hits.length === 0) return null;
  if (near == null) return hits[0];
  return hits.reduce((best, seq) => (Math.abs(seq - near) < Math.abs(best - near) ? seq : best));
}

/** Above this mean distance (metres) from stops to the road path, the path is not trusted. */
export const ALIGN_MAX_MEAN_M = 120;
/** Cost of each metre run along the path between stops, against detours and loops. */
const RUN_WEIGHT = 0.05;

function metresBetween(lat1, lng1, lat2, lng2) {
  const k = Math.cos(((lat1 + lat2) / 2) * (Math.PI / 180));
  const dy = (lat1 - lat2) * 111320;
  const dx = (lng1 - lng2) * 111320 * k;
  return Math.sqrt(dx * dx + dy * dy);
}

/** { at: Map seq -> path index, mean: metres } or null. */
function align(placed, path) {
  const stops = [...(placed || [])].sort((a, b) => a.seq - b.seq);
  const n = path?.length || 0;
  if (stops.length < 2 || n < 2) return null;
  const run = new Float64Array(n);
  for (let j = 1; j < n; j += 1) {
    run[j] = run[j - 1] + metresBetween(path[j - 1][0], path[j - 1][1], path[j][0], path[j][1]);
  }
  const back = new Int32Array(stops.length * n);
  let prev = new Float64Array(n);
  let cur = new Float64Array(n);
  for (let j = 0; j < n; j += 1) prev[j] = metresBetween(stops[0].lat, stops[0].lng, path[j][0], path[j][1]);
  for (let i = 1; i < stops.length; i += 1) {
    let best = Infinity;
    let bestAt = 0;
    for (let j = 0; j < n; j += 1) {
      const fromHere = prev[j] - RUN_WEIGHT * run[j];
      if (fromHere < best) {
        best = fromHere;
        bestAt = j;
      }
      cur[j] = best + RUN_WEIGHT * run[j] + metresBetween(stops[i].lat, stops[i].lng, path[j][0], path[j][1]);
      back[i * n + j] = bestAt;
    }
    [prev, cur] = [cur, prev];
  }
  let end = 0;
  for (let j = 1; j < n; j += 1) if (prev[j] < prev[end]) end = j;
  const at = new Map();
  let j = end;
  let off = 0;
  for (let i = stops.length - 1; i >= 0; i -= 1) {
    at.set(stops[i].seq, j);
    off += metresBetween(stops[i].lat, stops[i].lng, path[j][0], path[j][1]);
    if (i > 0) j = back[i * n + j];
  }
  return { at, mean: off / stops.length };
}

/**
 * Where each placed stop sits on the road path ([[lat, lng], ...] in running
 * order): a Map seq -> path index, never going back along the path, keeping
 * the stops close to the path with little run between them. A route passes
 * the same street twice (out and back), so the nearest point alone could put
 * a stop on the wrong pass; keeping the order avoids that. Null when the path
 * does not fit the stops (mean distance over ALIGN_MAX_MEAN_M).
 */
export function alignStopsToPath(placed, path) {
  const a = align(placed, path);
  return a && a.mean <= ALIGN_MAX_MEAN_M ? a.at : null;
}

/**
 * The road between two stops, inclusive. The stops from one to the other are
 * aligned to each of the route's paths (one, or several stored runs such as a
 * commuter route's morning and evening runs) and the best-fitting path gives
 * the line. Straight lines between the stops (pathBetween) when no path fits.
 */
export function roadBetween(placed, paths, fromSeq, toSeq) {
  if (fromSeq == null || toSeq == null) return [];
  const lo = Math.min(fromSeq, toSeq);
  const hi = Math.max(fromSeq, toSeq);
  const sub = (placed || []).filter((p) => p.seq >= lo && p.seq <= hi);
  const a = sub.find((p) => p.seq === lo);
  const b = sub.find((p) => p.seq === hi);
  let best = null;
  if (a && b && lo !== hi) {
    for (const path of paths || []) {
      const fit = align(sub, path);
      if (!fit || fit.mean > ALIGN_MAX_MEAN_M) continue;
      const ia = fit.at.get(lo);
      const ib = fit.at.get(hi);
      if (ib < ia) continue;
      if (!best || fit.mean < best.mean) best = { mean: fit.mean, line: path.slice(ia, ib + 1) };
    }
  }
  if (!best) return pathBetween(placed, fromSeq, toSeq);
  return [[a.lat, a.lng], ...best.line, [b.lat, b.lng]];
}
