import { describe, expect, test } from "vitest";

import { alignStopsToPath, boundsOf, pathBetween, placeStops, roadBetween, splitAtTurnaround } from "../routeGeometry.js";

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

  test("an ARS shared with a far stop of another name is not taken", () => {
    const route = [
      { seq: 1, ars: "07830", name: "판교역" },
      { seq: 2, ars: "07832", name: "판교, 해링턴" }
    ];
    const pts = [
      { nodeId: "07830", nodeName: "판교역", lat: 37.395, lng: 127.111 },
      { nodeId: "07832", nodeName: "중랑구청", lat: 37.606, lng: 127.093 }
    ];
    expect(placeStops(route, pts).map((p) => p.seq)).toEqual([1]);
    const withOwn = [{ nodeId: "07832", nodeName: "판교,해링턴", lat: 37.39, lng: 127.1 }, ...pts];
    expect(placeStops(route, withOwn)[1]).toMatchObject({ lat: 37.39 });
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

import { pathStopPoints } from "../routeGeometry.js";

describe("pathStopPoints", () => {
  test("stored route stops place what the demand layer lacks, and win over it", () => {
    const roadPath = {
      stops: [
        { ars: "90001", name: "마곡 선착장", lat: 37.57, lng: 126.83 },
        { ars: "01001", name: "시청", lat: 37.565, lng: 126.977 }
      ]
    };
    const demand = [{ nodeId: "01001", nodeName: "시청", lat: 37.5, lng: 127.0 }];
    const placed = placeStops(
      [
        { seq: 1, ars: "90001", name: "마곡 선착장" },
        { seq: 2, ars: "01001", name: "시청" }
      ],
      [...pathStopPoints(roadPath), ...demand]
    );
    expect(placed.map((p) => [p.ars, p.lat])).toEqual([
      ["90001", 37.57],
      ["01001", 37.565]
    ]);
  });
});

describe("reach and segment lines along the road", () => {
  // Out along lat 37.500 with a bend to 37.502 between stops 2 and 3, back the same street.
  const path = [
    [37.5, 127.0], [37.5, 127.001], [37.502, 127.0015], [37.5, 127.002], [37.5, 127.003],
    [37.5, 127.002], [37.5, 127.001], [37.5, 127.0]
  ];
  const placed = [
    { seq: 1, lat: 37.5, lng: 127.0 },
    { seq: 2, lat: 37.5, lng: 127.001 },
    { seq: 3, lat: 37.5, lng: 127.002 },
    { seq: 4, lat: 37.5, lng: 127.003 },
    { seq: 5, lat: 37.5, lng: 127.001 }
  ];

  test("stops keep their order along the path, the way back included", () => {
    const align = alignStopsToPath(placed, path);
    expect([...align.entries()]).toEqual([[5, 6], [4, 4], [3, 3], [2, 1], [1, 0]]);
  });

  test("the line between two stops takes the road's bend", () => {
    expect(roadBetween(placed, [path], 2, 3)).toEqual([
      [37.5, 127.001], [37.5, 127.001], [37.502, 127.0015], [37.5, 127.002], [37.5, 127.002]
    ]);
  });

  test("of several runs, the one the stops lie on", () => {
    const other = [[37.6, 127.1], [37.601, 127.1]];
    expect(roadBetween(placed, [other, path], 4, 5)).toEqual([
      [37.5, 127.003], [37.5, 127.003], [37.5, 127.002], [37.5, 127.001], [37.5, 127.001]
    ]);
  });

  test("straight lines when there is no path or it does not fit", () => {
    expect(roadBetween(placed, [], 2, 3)).toEqual([[37.5, 127.001], [37.5, 127.002]]);
    expect(alignStopsToPath(placed, [[37.6, 127.1], [37.61, 127.1]])).toBeNull();
  });
});
