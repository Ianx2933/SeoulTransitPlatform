import { describe, expect, test } from "vitest";

import { busRouteStyle, busRouteType } from "../busRouteType.js";

describe("busRouteType", () => {
  test.each([
    ["302", "trunk"],
    ["741", "trunk"],
    ["1711", "branch"],
    ["7212", "branch"],
    ["0017", "branch"],
    ["0411", "branch"],
    ["9404", "express"],
    ["9403-1", "express"],
    ["02", "circular"],
    ["강남01", "village"],
    ["서대문10", "village"],
    ["N13", "night"],
    ["N6001", "night"],
    ["A21", "trunk"],
    ["A01", "circular"],
    ["A741", "trunk"],
    ["한강버스", "ferry"],
    ["8146", "other"],
    ["", "other"]
  ])("%s is %s", (name, type) => {
    expect(busRouteType(name)).toBe(type);
  });

  test("ferry mode wins over the number", () => {
    expect(busRouteType("101", "ferry")).toBe("ferry");
  });

  test("night routes use yellow text on blue", () => {
    expect(busRouteStyle("N26")).toEqual({ bg: "#3D5BAB", fg: "#FFC600" });
  });
});
