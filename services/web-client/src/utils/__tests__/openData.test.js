import { describe, expect, test } from "vitest";

import { fileUrl, formatBytes, formatDay, manifestUrl, parseManifest, pickText } from "../openData.js";

const manifest = {
  release: "20261020",
  window_start: "20251021",
  window_end: "20261020",
  files: [{ name: "stops", file: "stops.csv.gz", rows: 3, bytes: 2048, columns: ["stop_key"] }]
};

describe("openData", () => {
  test("builds release links and the latest manifest URL", () => {
    expect(manifestUrl("https://x")).toBe("https://x/latest/manifest.json");
    expect(fileUrl("https://x", "20261020", "stops.csv.gz")).toBe("https://x/releases/20261020/stops.csv.gz");
  });

  test("formats days and sizes", () => {
    expect(formatDay("20261020")).toBe("2026-10-20");
    expect(formatDay("x")).toBe("x");
    expect(formatBytes(512)).toBe("512 B");
    expect(formatBytes(1536)).toBe("1.5 KB");
    expect(formatBytes(5 * 1024 * 1024)).toBe("5.0 MB");
    expect(formatBytes(null)).toBe("");
  });

  test("picks the interface language", () => {
    expect(pickText({ ko: "가", en: "a" }, "en")).toBe("a");
    expect(pickText({ ko: "가" }, "en")).toBe("가");
    expect(pickText("plain", "en")).toBe("plain");
    expect(pickText(undefined, "ko")).toBe("");
  });

  test("accepts a manifest it can show and rejects others", () => {
    expect(parseManifest(manifest)).toBe(manifest);
    expect(() => parseManifest(null)).toThrow();
    expect(() => parseManifest({ ...manifest, release: "" })).toThrow();
    expect(() => parseManifest({ ...manifest, files: [] })).toThrow();
    expect(() => parseManifest({ ...manifest, files: [{ file: "a.json" }] })).toThrow();
  });
});
