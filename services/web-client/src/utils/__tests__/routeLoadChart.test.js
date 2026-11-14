import { describe, expect, test } from "vitest";

import {
  buildBarChart,
  buildLoadChart,
  findTurnaround,
  percent,
  stopsBetween
} from "../routeLoadChart.js";

const stops = [
  { seq: 1, onBoardAfter: 40 },
  { seq: 2, onBoardAfter: 80 },
  { seq: 3, onBoardAfter: 0 }
];

describe("buildLoadChart", () => {
  test("puts the highest load at the top and zero on the axis", () => {
    const chart = buildLoadChart(stops, { width: 248, height: 148, padding: 24 });
    expect(chart.maxLoad).toBe(80);
    expect(chart.points.map((p) => p.x)).toEqual([24, 124, 224]);
    expect(chart.points.map((p) => p.y)).toEqual([74, 24, 124]);
    expect(chart.areaPath.startsWith("M24,74 L124,24 L224,124")).toBe(true);
    expect(chart.areaPath.endsWith("Z")).toBe(true);
  });

  test("returns an empty chart for no stops", () => {
    expect(buildLoadChart([], { width: 100, height: 100 }).points).toEqual([]);
  });
});

describe("stopsBetween and percent", () => {
  test("selects inclusive ranges in either order", () => {
    expect(stopsBetween(stops, 3, 2).map((s) => s.seq)).toEqual([2, 3]);
  });

  test("formats shares and guards a zero total", () => {
    expect(percent(25, 200)).toBe("12.5%");
    expect(percent(5, 0)).toBe("-");
  });
});

describe("buildBarChart", () => {
  test("one bar per stop, the tallest reaching the top of the plot", () => {
    const chart = buildBarChart(stops, { width: 352, height: 168, padLeft: 40, padRight: 12, padTop: 28, padBottom: 40 });
    // inner 300 x 100, slot 100, bar 75
    expect(chart.bars.map((b) => b.x)).toEqual([52.5, 152.5, 252.5]);
    expect(chart.bars.map((b) => b.h)).toEqual([50, 100, 0]);
    expect(chart.bars[1].y).toBe(28);
    expect(chart.baseY).toBe(128);
    expect(chart.labelEvery).toBe(1);
  });

  test("labels thin out when stops are dense", () => {
    const many = Array.from({ length: 100 }, (_, i) => ({ seq: i + 1, onBoardAfter: i }));
    const chart = buildBarChart(many, { width: 552, height: 200 });
    expect(chart.labelEvery).toBeGreaterThan(1);
  });
});

describe("findTurnaround", () => {
  test("takes the median midpoint of names served in both directions", () => {
    const route = ["A", "B", "C", "D", "E", "D", "C", "B", "A"].map((name, i) => ({ seq: i + 1, name }));
    expect(findTurnaround(route)).toBe(5);
  });

  test("returns null for a route with no repeated names", () => {
    const route = ["A", "B", "C", "D"].map((name, i) => ({ seq: i + 1, name }));
    expect(findTurnaround(route)).toBeNull();
  });
});
