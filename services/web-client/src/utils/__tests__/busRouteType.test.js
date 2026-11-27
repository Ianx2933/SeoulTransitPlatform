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
    ["서울01", "express"],
    ["서울01출근", "express"],
    ["서울03", "express"],
    ["서울06퇴근", "express"],
    ["서울02", "trunk"],
    ["서울10", "trunk"],
    ["동대문A01", "trunk"],
    ["동작A01", "trunk"],
    ["서대문A01", "trunk"],
    ["새벽A148", "trunk"],
    ["새벽A741", "trunk"],
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
