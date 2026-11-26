import { describe, expect, test } from "vitest";

import { TYPICAL } from "../basis.js";
import { parseRouteLink, routeLinkHash } from "../routeLink.js";

describe("route links", () => {
  test("round trip of route, day type, months and hours", () => {
    const state = {
      route: "3411",
      dayType: "saturday",
      basis: { ...TYPICAL, period: { from: "2024-09", to: "2024-11" }, hours: { from: 7, to: 9 } },
      fleet: { vehicles: 20, trips: 150 }
    };
    const hash = routeLinkHash(state);
    expect(hash).toBe("#/route?r=3411&d=saturday&p=2024-09..2024-11&h=7-9&v=20&t=150");
    expect(parseRouteLink(hash)).toEqual(state);
  });

  test("the typical weekday is the short form", () => {
    expect(routeLinkHash({ route: "5522A난곡", dayType: "weekday", basis: TYPICAL })).toBe(
      "#/route?r=5522A%EB%82%9C%EA%B3%A1"
    );
    expect(parseRouteLink("#/route?r=5522A%EB%82%9C%EA%B3%A1").route).toBe("5522A난곡");
  });

  test("bad parts are dropped, no route is no link", () => {
    const s = parseRouteLink("#/route?r=1155&d=holiday&p=2024-11..2024-01&h=25");
    expect(s.dayType).toBe("weekday");
    expect(s.basis.period).toBeNull();
    expect(s.basis.hours).toBeNull();
    expect(parseRouteLink("#/route?r=1155&v=-3&t=x").fleet).toEqual({ vehicles: null, trips: null });
    expect(parseRouteLink("#/route")).toBeNull();
    expect(parseRouteLink("#/map?r=1")).toBeNull();
  });
});
