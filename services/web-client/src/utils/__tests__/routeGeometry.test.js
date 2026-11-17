import { describe, expect, test } from "vitest";

import { boundsOf, pathBetween, placeStops, splitAtTurnaround } from "../routeGeometry.js";

const stops = [
  { seq: 1, ars: "01001", name: "기점" },
  { seq: 2, ars: "1002", name: "시장 앞" },
  { seq: 3, ars: "9999", name: "반환점" },
  { seq: 4, ars: "8888", name: "시장앞" },
  { seq: 5, ars: "7777", name: "없는 정류소" }
];

const points = [
  { nodeId: "1001", nodeName: "기점", lat: 37.5, lng: 127.0 },
  { nodeId: "01002", nodeName: "시장 앞", lat: 37.51, lng: 127.0 },
  { nodeId: "9999", nodeName: "반환점", lat: 37.52, lng: 127.0 },
  { nodeId: "5555", nodeName: "시장앞", lat: 37.511, lng: 127.001 },
  { nodeId: "6666", nodeName: "시장앞", lat: 37.6, lng: 127.2 }
];

describe("placeStops", () => {
  test("matches by ARS without leading zeros, then by nearest same name", () => {
    const placed = placeStops(stops, points);
    expect(placed.map((p) => p.seq)).toEqual([1, 2, 3, 4]);
    expect(placed[3]).toMatchObject({ lat: 37.511, lng: 127.001 });
  });

  test("ignores points without coordinates", () => {
    expect(placeStops(stops, [{ nodeId: "1001", lat: null, lng: 1 }])).toEqual([]);
  });
});

describe("splitAtTurnaround", () => {
  test("shares the turnaround stop between the two lines", () => {
    const placed = placeStops(stops, points);
    const { up, down } = splitAtTurnaround(placed, 3);
    expect(up).toHaveLength(3);
    expect(down[0]).toEqual(up[2]);
    expect(down).toHaveLength(2);
  });

  test("is all upbound without a turnaround", () => {
    expect(splitAtTurnaround(placeStops(stops, points), null).down).toEqual([]);
  });
});

describe("pathBetween and boundsOf", () => {
  test("cuts the line and frames the stops", () => {
    const placed = placeStops(stops, points);
    expect(pathBetween(placed, 3, 2)).toHaveLength(2);
    expect(boundsOf(placed)).toEqual([[37.5, 127.0], [37.52, 127.001]]);
    expect(boundsOf([])).toBeNull();
  });
});

import { seqOfArs } from "../routeGeometry.js";

describe("seqOfArs", () => {
  const route = [
    { seq: 1, ars: "01001" },
    { seq: 30, ars: "48028" },
    { seq: 63, ars: "24117" },
    { seq: 90, ars: "48028" }
  ];

  test("finds the stop by ARS ignoring leading zeros", () => {
    expect(seqOfArs(route, "1001")).toBe(1);
    expect(seqOfArs(route, "24117")).toBe(63);
    expect(seqOfArs(route, "99999")).toBeNull();
    expect(seqOfArs(route, null)).toBeNull();
  });

  test("takes the occurrence nearest the estimate when a stop is served twice", () => {
    expect(seqOfArs(route, "48028", 80)).toBe(90);
    expect(seqOfArs(route, "48028")).toBe(30);
  });
});
