import { describe, expect, test } from "vitest";

import { sameEnds, segmentDayRows, segmentTotal } from "../segmentDays.js";

const seg = (k, extra = {}) => ({
  fromName: "가",
  toName: "나",
  within: 10 * k,
  leaving: 20 * k,
  entering: 30 * k,
  through: 40 * k,
  routeTotal: 1000 * k,
  ...extra
});

describe("segment by day type", () => {
  test("totals and weekend shares of the weekday", () => {
    const rows = segmentDayRows({ weekday: seg(1), saturday: seg(0.6), sunday_holiday: seg(0.45) });
    const total = rows.find((r) => r.key === "total");
    expect(total.values).toEqual({ weekday: 100, saturday: 60, sunday_holiday: 45 });
    expect(total.shares).toEqual({ saturday: 60, sunday_holiday: 45 });
    expect(rows.map((r) => r.key)).toEqual(["total", "within", "leaving", "entering", "through", "routeTotal"]);
  });

  test("a missing day type stays empty", () => {
    const rows = segmentDayRows({ weekday: seg(1), saturday: null, sunday_holiday: seg(0.5) });
    expect(rows[0].values.saturday).toBeNull();
    expect(rows[0].shares.saturday).toBeNull();
    expect(segmentTotal(null)).toBeNull();
  });

  test("ends must be the same stops", () => {
    expect(sameEnds(seg(1), seg(2))).toBe(true);
    expect(sameEnds(seg(1, { toName: "다" }), seg(1))).toBe(false);
  });
});
