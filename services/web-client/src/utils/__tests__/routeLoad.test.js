import { describe, expect, test } from "vitest";

import {
  busiestRoutes,
  legendSteps,
  LOAD_COLORS,
  loadBounds,
  loadOn,
  routeBreakdown,
  styleSegments
} from "../routeLoad.js";

const seg = (load, routes = ["302"], path = [[37.5, 127], [37.51, 127.01]], byRoute) => ({ load, routes, path, byRoute });

describe("routeLoad", () => {
  test("ten steps by quantile, thicker for more riders, busiest drawn last", () => {
    const loads = Array.from({ length: 100 }, (_, i) => i + 1);
    const styled = styleSegments([...loads.map((l) => seg(l)), seg(0), seg(3, ["x"], [[1, 1]])]);
    expect(styled.shown).toBe(100);
    expect(styled.breaks).toHaveLength(9);
    expect(styled.segments[0].color).toBe(LOAD_COLORS[0]);
    expect(styled.segments[99].color).toBe(LOAD_COLORS[9]);
    expect(styled.segments[99].weight).toBeGreaterThan(styled.segments[0].weight);
    expect(styled.max).toBe(100);
  });

  test("segments under the minimum are hidden", () => {
    const styled = styleSegments([seg(1), seg(4), seg(5), seg(40), seg(400)], { minLoad: 5 });
    expect(styled.segments.map((s) => s.load)).toEqual([5, 40, 400]);
    expect(styled.hidden).toBe(2);
  });

  test("chosen routes keep only their own riders", () => {
    const a = seg(30, ["302", "440"], undefined, { 302: 20, 440: 10 });
    const b = seg(8, ["440"], undefined, { 440: 8 });
    const c = seg(5, ["333"], undefined, { 333: 5 });
    expect(loadOn(a, ["440"])).toBe(10);
    expect(loadOn(a, [])).toBe(30);
    const styled = styleSegments([a, b, c], { routes: ["440"] });
    expect(styled.segments.map((s) => [s.load, s.routes])).toEqual([[8, ["440"]], [10, ["440"]]]);
  });

  test("legend lists each colour with its rider range", () => {
    const styled = styleSegments(Array.from({ length: 20 }, (_, i) => seg((i + 1) * 10)), { minLoad: 10 });
    const steps = legendSteps(styled, 10);
    expect(steps[0]).toMatchObject({ color: LOAD_COLORS[0], from: 10 });
    expect(steps.at(-1)).toMatchObject({ to: 200, last: true });
    for (let i = 1; i < steps.length; i += 1) expect(steps[i].from).toBe(steps[i - 1].to);
    expect(legendSteps(styleSegments([]))).toEqual([]);
  });

  test("bounds cover every point", () => {
    expect(loadBounds([seg(1), seg(2, [], [[37.4, 126.9], [37.6, 127.2]])])).toEqual([[37.4, 126.9], [37.6, 127.2]]);
    expect(loadBounds([])).toBeNull();
  });

  test("routes ranked by riders on their busiest segment", () => {
    expect(busiestRoutes([seg(5, ["a", "b"], undefined, { a: 2, b: 3 }), seg(9, ["b"]), seg(2, ["c"])])).toEqual([
      { route: "b", load: 9 },
      { route: "a", load: 2 },
      { route: "c", load: 2 }
    ]);
  });

  test("tooltip lists routes by riders, most first", () => {
    const s = seg(60, ["302", "440", "333"], undefined, { 302: 10, 440: 40, 333: 10 });
    expect(routeBreakdown(s)).toBe("440 40 · 302 10 · 333 10");
    expect(routeBreakdown(s, String, 1)).toBe("440 40 · +2");
    expect(routeBreakdown(seg(5, ["a"]))).toBe("a");
  });
});
