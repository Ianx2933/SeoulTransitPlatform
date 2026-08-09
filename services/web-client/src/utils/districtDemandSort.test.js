import { describe, expect, test } from "vitest";

import { sortDistrictNodes, sortDistrictRoutes } from "../districtDemandSort.js";

/**
 * Unit tests for districtDemandSort.
 *
 * These mirror the style of nodeDetailSort.test.js: plain fixture arrays,
 * one assertion per behavior, and explicit checks that inputs are never
 * mutated (both helpers promise to return new arrays).
 */

const sampleRoutes = [
  { serviceId: "N26", boarding: 10, alighting: 40, total: 50 },
  { serviceId: "146", boarding: 300, alighting: 100, total: 400 },
  { serviceId: "2호선", boarding: 90, alighting: 10, total: 100 },
  { serviceId: "9", boarding: 5, alighting: 15, total: 20 }
];

const sampleNodes = [
  { serviceId: "146", nodeName: "판교역", boarding: 70, alighting: 30, total: 100 },
  { serviceId: "9", nodeName: "강남역", boarding: 10, alighting: 40, total: 50 },
  { serviceId: "N26", nodeName: "서현역", boarding: 200, alighting: 100, total: 300 }
];

describe("sortDistrictRoutes", () => {
  test("totalDesc orders routes by total demand, highest first", () => {
    const sorted = sortDistrictRoutes(sampleRoutes, "totalDesc");
    expect(sorted.map((route) => route.serviceId)).toEqual(["146", "2호선", "N26", "9"]);
  });

  test("totalAsc orders routes by total demand, lowest first", () => {
    const sorted = sortDistrictRoutes(sampleRoutes, "totalAsc");
    expect(sorted.map((route) => route.serviceId)).toEqual(["9", "N26", "2호선", "146"]);
  });

  test("routeAsc uses numeric-aware compare so 9 comes before 146", () => {
    const sorted = sortDistrictRoutes(sampleRoutes, "routeAsc");
    const nineIndex = sorted.findIndex((route) => route.serviceId === "9");
    const oneFourSixIndex = sorted.findIndex((route) => route.serviceId === "146");
    expect(nineIndex).toBeLessThan(oneFourSixIndex);
  });

  test("boardingDesc orders routes by boarding", () => {
    const sorted = sortDistrictRoutes(sampleRoutes, "boardingDesc");
    expect(sorted[0].serviceId).toBe("146");
    expect(sorted[sorted.length - 1].serviceId).toBe("9");
  });

  test("unknown sort key returns a copy in original order", () => {
    const sorted = sortDistrictRoutes(sampleRoutes, "definitely-not-a-key");
    expect(sorted.map((route) => route.serviceId)).toEqual(["N26", "146", "2호선", "9"]);
    expect(sorted).not.toBe(sampleRoutes);
  });

  test("does not mutate the input array", () => {
    const original = sampleRoutes.map((route) => route.serviceId);
    sortDistrictRoutes(sampleRoutes, "totalDesc");
    expect(sampleRoutes.map((route) => route.serviceId)).toEqual(original);
  });

  test("treats missing numeric fields as zero", () => {
    const withMissing = [
      { serviceId: "A" },
      { serviceId: "B", total: 10 }
    ];
    const sorted = sortDistrictRoutes(withMissing, "totalDesc");
    expect(sorted[0].serviceId).toBe("B");
  });
});

describe("sortDistrictNodes", () => {
  test("totalDesc orders nodes by total demand, highest first", () => {
    const sorted = sortDistrictNodes(sampleNodes, "totalDesc");
    expect(sorted.map((node) => node.nodeName)).toEqual(["서현역", "판교역", "강남역"]);
  });

  test("alightingDesc orders nodes by alighting", () => {
    const sorted = sortDistrictNodes(sampleNodes, "alightingDesc");
    expect(sorted[0].nodeName).toBe("서현역");
  });

  test("nameAsc sorts Korean node names with locale compare", () => {
    const sorted = sortDistrictNodes(sampleNodes, "nameAsc");
    expect(sorted.map((node) => node.nodeName)).toEqual(["강남역", "서현역", "판교역"]);
  });

  test("routeAsc uses numeric-aware compare on serviceId", () => {
    const sorted = sortDistrictNodes(sampleNodes, "routeAsc");
    const nineIndex = sorted.findIndex((node) => node.serviceId === "9");
    const oneFourSixIndex = sorted.findIndex((node) => node.serviceId === "146");
    expect(nineIndex).toBeLessThan(oneFourSixIndex);
  });

  test("unknown sort key returns a copy in original order without mutation", () => {
    const original = sampleNodes.map((node) => node.nodeName);
    const sorted = sortDistrictNodes(sampleNodes, "not-a-key");
    expect(sorted.map((node) => node.nodeName)).toEqual(original);
    expect(sorted).not.toBe(sampleNodes);
    expect(sampleNodes.map((node) => node.nodeName)).toEqual(original);
  });
});
