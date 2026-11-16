import { describe, expect, test } from "vitest";

import { routeQuery } from "../../api/routeAnalysisApi.js";
import { viewFromHash } from "../../hooks/useHashView.js";

describe("routeQuery", () => {
  test("sends the hour on weekdays only", () => {
    expect(routeQuery({ dayType: "weekday", hour: 8 })).toBe("date=weekday&hour=8");
    expect(routeQuery({ dayType: "saturday", hour: 8 })).toBe("date=saturday");
    expect(routeQuery({ dayType: "weekday", hour: 0 })).toBe("date=weekday&hour=0");
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
