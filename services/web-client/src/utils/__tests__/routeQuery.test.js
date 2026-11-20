import { describe, expect, test } from "vitest";

import { routeQuery } from "../../api/routeAnalysisApi.js";
import { viewFromHash } from "../../hooks/useHashView.js";

describe("routeQuery", () => {
  test("sends boarding hours on any day type", () => {
    expect(routeQuery({ dayType: "weekday", basis: { hours: { from: 8, to: 8 } } })).toBe("date=weekday&hours=8");
    expect(routeQuery({ dayType: "saturday", basis: { hours: { from: 0, to: 0 } } })).toBe("date=saturday&hours=0");
    expect(routeQuery({ dayType: "weekday" })).toBe("date=weekday");
  });

  test("joins reach levels and adds a section", () => {
    expect(routeQuery({ dayType: "weekday", reach: [30, 300] })).toBe("date=weekday&reach=30%2C300");
    expect(routeQuery({ dayType: "weekday", from: 1, to: 22 })).toBe("date=weekday&from=1&to=22");
  });
});

describe("viewFromHash", () => {
  test("reads the view and falls back to the map", () => {
    expect(viewFromHash("#/route")).toBe("route");
    expect(viewFromHash("#/method?x=1")).toBe("method");
    expect(viewFromHash("")).toBe("map");
    expect(viewFromHash("#/nope")).toBe("map");
  });
});
