import { describe, expect, test } from "vitest";

import { classOf, colorOf, DEMAND_COLORS, dongDayType, quantileBreaks } from "../dongColor.js";

describe("dongColor", () => {
  const values = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10];

  test("splits positive values into five classes from green to red", () => {
    const breaks = quantileBreaks(values);
    expect(breaks).toEqual([3, 5, 7, 9]);
    expect(colorOf(1, breaks)).toBe(DEMAND_COLORS[0]);
    expect(colorOf(10, breaks)).toBe(DEMAND_COLORS[4]);
    expect(classOf(0, breaks)).toBe(-1);
    expect(quantileBreaks([0, -1])).toEqual([]);
  });

  test("picks the district OD day type from the map filter", () => {
    expect(dongDayType(["mon", "tue"])).toBe("weekday");
    expect(dongDayType(["sat"])).toBe("saturday");
    expect(dongDayType(["sun_holiday"])).toBe("sunday_holiday");
    expect(dongDayType(["sat", "sun_holiday"])).toBe("weekday");
    expect(dongDayType(undefined)).toBe("weekday");
  });
});
