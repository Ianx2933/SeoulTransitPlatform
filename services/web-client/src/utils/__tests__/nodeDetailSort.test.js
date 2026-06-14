import { describe, expect, test } from "vitest";

import { sortCatchmentNodes, sortRoutes } from "../nodeDetailSort.js";

/**
 * Unit tests for nodeDetailSort.
 *
 * These tests use Vitest's globals-free API. If the project uses Jest instead,
 * change the import to `from "@jest/globals"` and the same suites will run
 * without further changes.
 */

const sampleNodes = [
  { nodeName: "강남", distanceMeters: 200, total: 1000 },
  { nodeName: "서현", distanceMeters: 50, total: 5000 },
  { nodeName: "판교", distanceMeters: 500, total: 2000 },
  { nodeName: "양재", distanceMeters: 50, total: 3000 }
];

const sampleRoutes = [
  { mode: "bus", serviceId: "9401", boarding: 300, alighting: 270, total: 570 },
  { mode: "bus", serviceId: "422", boarding: 113, alighting: 94, total: 207 },
  { mode: "subway", serviceId: "2호선", boarding: 5000, alighting: 4800, total: 9800 },
  { mode: "bus", serviceId: "146", boarding: 200, alighting: 180, total: 380 }
];

describe("sortCatchmentNodes", () => {
  test("distanceAsc sorts ascending by distance (stable for ties)", () => {
    // 서현 and 양재 share distance 50m. Array.prototype.sort is stable in
    // modern engines, so the original relative order (서현 before 양재) holds.
    const sorted = sortCatchmentNodes(sampleNodes, "distanceAsc");
    expect(sorted.map((n) => n.nodeName)).toEqual([
      "서현",
      "양재",
      "강남",
      "판교"
    ]);
  });

  test("distanceDesc sorts descending by distance", () => {
    const sorted = sortCatchmentNodes(sampleNodes, "distanceDesc");
    expect(sorted[0].nodeName).toBe("판교");
  });

  test("totalDesc sorts descending by total demand", () => {
    const sorted = sortCatchmentNodes(sampleNodes, "totalDesc");
    expect(sorted.map((n) => n.nodeName)).toEqual([
      "서현",
      "양재",
      "판교",
      "강남"
    ]);
  });

  test("totalAsc sorts ascending by total demand", () => {
    const sorted = sortCatchmentNodes(sampleNodes, "totalAsc");
    expect(sorted[0].nodeName).toBe("강남");
    expect(sorted[3].nodeName).toBe("서현");
  });

  test("nameAsc sorts alphabetically using Korean locale", () => {
    const sorted = sortCatchmentNodes(sampleNodes, "nameAsc");
    expect(sorted.map((n) => n.nodeName)).toEqual([
      "강남",
      "서현",
      "양재",
      "판교"
    ]);
  });

  test("nameDesc sorts reverse alphabetically", () => {
    const sorted = sortCatchmentNodes(sampleNodes, "nameDesc");
    expect(sorted[0].nodeName).toBe("판교");
    expect(sorted[3].nodeName).toBe("강남");
  });

  test("unknown sort key returns a copy in original order", () => {
    const sorted = sortCatchmentNodes(sampleNodes, "no-such-key");
    expect(sorted.map((n) => n.nodeName)).toEqual([
      "강남",
      "서현",
      "판교",
      "양재"
    ]);
  });

  test("does not mutate the input array", () => {
    const originalOrder = sampleNodes.map((n) => n.nodeName);
    sortCatchmentNodes(sampleNodes, "totalDesc");
    expect(sampleNodes.map((n) => n.nodeName)).toEqual(originalOrder);
  });

  test("handles missing distance and total values as zero", () => {
    const nodes = [
      { nodeName: "A", distanceMeters: 100, total: 50 },
      { nodeName: "B" },
      { nodeName: "C", distanceMeters: 200 }
    ];
    const byDistance = sortCatchmentNodes(nodes, "distanceAsc");
    expect(byDistance.map((n) => n.nodeName)).toEqual(["B", "A", "C"]);
  });
});

describe("sortRoutes", () => {
  test("totalDesc sorts descending by total", () => {
    const sorted = sortRoutes(sampleRoutes, "totalDesc");
    expect(sorted.map((r) => r.serviceId)).toEqual([
      "2호선",
      "9401",
      "146",
      "422"
    ]);
  });

  test("totalAsc sorts ascending by total", () => {
    const sorted = sortRoutes(sampleRoutes, "totalAsc");
    expect(sorted[0].serviceId).toBe("422");
    expect(sorted[3].serviceId).toBe("2호선");
  });

  test("routeAsc sorts route IDs with numeric-aware Korean locale ordering", () => {
    // Korean locale + numeric:true on this dataset puts Hangul before digits
    // and then sorts the digit groups numerically: 2호선 < 146 < 422 < 9401.
    const sorted = sortRoutes(sampleRoutes, "routeAsc");
    expect(sorted.map((r) => r.serviceId)).toEqual([
      "2호선",
      "146",
      "422",
      "9401"
    ]);
  });

  test("routeDesc reverses the routeAsc order", () => {
    const sorted = sortRoutes(sampleRoutes, "routeDesc");
    expect(sorted.map((r) => r.serviceId)).toEqual([
      "9401",
      "422",
      "146",
      "2호선"
    ]);
  });

  test("boardingDesc sorts descending by boarding count", () => {
    const sorted = sortRoutes(sampleRoutes, "boardingDesc");
    expect(sorted[0].serviceId).toBe("2호선");
    expect(sorted[3].serviceId).toBe("422");
  });

  test("alightingDesc sorts descending by alighting count", () => {
    const sorted = sortRoutes(sampleRoutes, "alightingDesc");
    expect(sorted[0].serviceId).toBe("2호선");
    expect(sorted[3].serviceId).toBe("422");
  });

  test("unknown sort key returns a copy in original order", () => {
    const sorted = sortRoutes(sampleRoutes, "no-such-key");
    expect(sorted.map((r) => r.serviceId)).toEqual([
      "9401",
      "422",
      "2호선",
      "146"
    ]);
  });

  test("does not mutate the input array", () => {
    const originalOrder = sampleRoutes.map((r) => r.serviceId);
    sortRoutes(sampleRoutes, "totalDesc");
    expect(sampleRoutes.map((r) => r.serviceId)).toEqual(originalOrder);
  });

  test("handles missing numeric fields gracefully", () => {
    const routes = [
      { mode: "bus", serviceId: "X" },
      { mode: "bus", serviceId: "Y", total: 100, boarding: 50, alighting: 50 }
    ];
    const byTotal = sortRoutes(routes, "totalDesc");
    expect(byTotal.map((r) => r.serviceId)).toEqual(["Y", "X"]);
  });
});
