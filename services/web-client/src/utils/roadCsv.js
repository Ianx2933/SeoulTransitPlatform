/**
 * CSV of a road-load result (district road view, routes, stop or segment):
 * one row per segment, then the stops riders board and alight at when the
 * result has them. Every segment is included, not only those on the map.
 * Pure, for tests; downloadCsv does the browser part.
 */
const HEADER = ["type", "from_ars", "to_ars", "stop_ars", "stop_name", "lat", "lng", "riders", "routes"];

function cell(value) {
  if (value === null || value === undefined) return "";
  const text = String(value);
  return /[",\n\r]/.test(text) ? `"${text.replace(/"/g, '""')}"` : text;
}

function routesText(segment) {
  const byRoute = segment.byRoute || {};
  return (segment.routes || [])
    .map((r) => (byRoute[r] == null ? r : `${r}:${byRoute[r]}`))
    .join(";");
}

export function roadCsv(result) {
  const rows = [HEADER];
  for (const s of result?.segments || []) {
    rows.push(["segment", s.fromArs, s.toArs, "", "", "", "", s.load, routesText(s)]);
  }
  for (const [type, points] of [["origin", result?.origins], ["destination", result?.destinations]]) {
    for (const p of points || []) {
      rows.push([type, "", "", p.ars, p.name, p.lat, p.lng, p.riders, ""]);
    }
  }
  return rows.map((r) => r.map(cell).join(",")).join("\r\n") + "\r\n";
}

/** File name from parts, keeping letters, digits, Hangul, dot, dash. */
export function csvFileName(...parts) {
  const name = parts
    .filter((p) => p !== null && p !== undefined && p !== "")
    .map((p) => String(p).replace(/[^\p{L}\p{N}.-]+/gu, "_").replace(/^_+|_+$/g, ""))
    .filter(Boolean)
    .join("-");
  return `${name || "road-load"}.csv`;
}

/** Saves the CSV with a byte-order mark so Excel reads Korean names. */
export function downloadCsv(text, fileName) {
  const blob = new Blob(["﻿", text], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = fileName;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
