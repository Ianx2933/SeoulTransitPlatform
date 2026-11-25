import { describe, expect, test } from "vitest";

import { isPastRoute, pastPoints, pastSegments, periodEndDay, stopMatch, stopPathQuery } from "../pastRoute.js";

const stops = [
  { seq: 2, ars: "1002", name: "나", onBoardAfter: 30 },
  { seq: 1, ars: "01001", name: "가", onBoardAfter: 10 },
  { seq: 3, ars: "01003", name: "다", onBoardAfter: 0 },
  { seq: 4, ars: "01003", name: "다", onBoardAfter: 0 }
];

describe("stopMatch", () => {
  test("shares of OD stops on the current path", () => {
    const m = stopMatch(stops, { stops: [{ ars: "01001" }, { ars: "09999" }] });
    expect(m).toEqual({ matched: 1, total: 3, ratio: 1 / 3 });
    expect(isPastRoute(m)).toBe(true);
  });

  test("no stored stops means no comparison", () => {
    expect(stopMatch(stops, { stops: [] })).toBeNull();
    expect(isPastRoute(null)).toBe(false);
  });

  test("a partly rerouted route is past, one or two odd stops are not", () => {
    expect(isPastRoute({ matched: 50, total: 55, ratio: 50 / 55 })).toBe(true);
    expect(isPastRoute({ matched: 53, total: 55, ratio: 53 / 55 })).toBe(false);
  });

  test("a matching route is not past", () => {
    const m = stopMatch(stops, { stops: [{ ars: "1001" }, { ars: "1002" }, { ars: "1003" }] });
    expect(isPastRoute(m)).toBe(false);
  });
});

describe("periodEndDay", () => {
  test("the chosen day, the last day of the last month, or none", () => {
    expect(periodEndDay({ day: "2026-01-13" })).toBe("2026-01-13");
    expect(periodEndDay({ from: "2024-09", to: "2024-11" })).toBe("2024-11-30");
    expect(periodEndDay({ from: "2024-02", to: "2024-02" })).toBe("2024-02-29");
    expect(periodEndDay(null)).toBeNull();
  });
});

describe("pastSegments with master stops", () => {
  test("a stop the OD does not list keeps the riders before it", () => {
    const path = {
      segments: [
        { fromArs: "01001", toArs: "01009", path: [[37.5, 127], [37.505, 127]] },
        { fromArs: "01009", toArs: "01002", path: [[37.505, 127], [37.51, 127]] }
      ]
    };
    const out = pastSegments([{ seq: 1, ars: "01001", onBoardAfter: 12 }, { seq: 2, ars: "01002", onBoardAfter: 0 }], path);
    expect(out.map((s) => s.load)).toEqual([12, 12]);
  });
});

describe("stopPathQuery", () => {
  test("riding order, padded, repeats dropped", () => {
    expect(stopPathQuery(stops)).toBe("01001,01002,01003");
    expect(stopPathQuery([{ seq: 1, ars: "1" }])).toBeNull();
  });
});

describe("pastSegments", () => {
  test("colours by riders after the first stop, grey when none", () => {
    const path = {
      stops: [{ ars: "01001", name: "가", lat: 37.5, lng: 127 }],
      segments: [
        { fromArs: "01001", toArs: "01002", path: [[37.5, 127], [37.51, 127]], straight: false },
        { fromArs: "01002", toArs: "01003", path: [[37.51, 127], [37.52, 127]], straight: true }
      ]
    };
    const out = pastSegments(stops, path);
    expect(out).toHaveLength(2);
    expect(out.map((s) => s.load)).toEqual([10, 30]);
    expect(out.every((s) => s.color)).toBe(true);
    expect(out[1].straight).toBe(true);

    const none = pastSegments([{ seq: 1, ars: "01001", onBoardAfter: 0 }], { segments: path.segments.slice(0, 1) });
    expect(none[0].level).toBe(-1);
    expect(pastPoints(path)[0]).toEqual({ nodeId: "01001", nodeName: "가", lat: 37.5, lng: 127 });
  });
});
