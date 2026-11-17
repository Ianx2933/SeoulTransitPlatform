/**
 * Places route-analysis stops on the map using the coordinates the map demand
 * API returns for the same route. Kept free of React for tests.
 *
 * Stops are matched by ARS number first (the API's nodeId, compared without
 * leading zeros). A stop left over is matched by name; when the name occurs
 * more than once (both directions), the point nearest the previous placed
 * stop is taken. Stops with no match are left out of the line.
 */

const normaliseId = (value) => String(value ?? "").trim().replace(/^0+(?=\d)/, "");
const normaliseName = (value) => String(value ?? "").replace(/\s+/g, "");

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
