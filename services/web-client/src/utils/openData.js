/**
 * Open data download files: the public bucket written by transit-bigquery's
 * open_data_export.py each week. latest/manifest.json lists the newest release;
 * links point at releases/<release>/ so a saved link keeps naming the same file.
 */
export const OPEN_DATA_BASE =
  import.meta.env?.VITE_OPEN_DATA_BASE || "https://storage.googleapis.com/seoul-transit-open-data";

export const manifestUrl = (base = OPEN_DATA_BASE) => `${base}/latest/manifest.json`;

export function fileUrl(base, release, file) {
  return `${base}/releases/${encodeURIComponent(release)}/${encodeURIComponent(file)}`;
}

/** "20261020" -> "2026-10-20"; anything else is returned as it is. */
export function formatDay(day) {
  const s = String(day ?? "");
  return /^\d{8}$/.test(s) ? `${s.slice(0, 4)}-${s.slice(4, 6)}-${s.slice(6)}` : s;
}

/** 1536 -> "1.5 KB"; null -> "". */
export function formatBytes(bytes) {
  if (bytes == null || !Number.isFinite(Number(bytes))) return "";
  let n = Number(bytes);
  const units = ["B", "KB", "MB", "GB"];
  let i = 0;
  while (n >= 1024 && i < units.length - 1) {
    n /= 1024;
    i += 1;
  }
  return `${i === 0 ? n : n.toFixed(1)} ${units[i]}`;
}

/** Text in the interface language, falling back to Korean, then English. */
export function pickText(value, lang) {
  if (value == null || typeof value === "string") return value ?? "";
  return value[lang] ?? value.ko ?? value.en ?? "";
}

/** Checks the fields the page reads; throws on a manifest it cannot show. */
export function parseManifest(json) {
  if (!json || typeof json !== "object") throw new Error("manifest is not an object");
  if (!/^\d{8}$/.test(String(json.release ?? ""))) throw new Error("manifest has no release day");
  if (!Array.isArray(json.files) || json.files.length === 0) throw new Error("manifest lists no files");
  for (const f of json.files) {
    if (!f || typeof f.file !== "string" || !f.file.endsWith(".csv.gz")) {
      throw new Error("manifest file entry without a .csv.gz name");
    }
  }
  return json;
}
