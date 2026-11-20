import { describe, expect, test } from "vitest";

import {
  addMonths,
  appendBasis,
  basisFileTag,
  basisKey,
  clampPeriod,
  defaultPeriod,
  fixedBreaks,
  fixedScaleMax,
  hoursRange,
  isPeriodUnavailable,
  isPlain,
  monthsList,
  setHoursEnd,
  shiftHours,
  TYPICAL
} from "../basis.js";
import { routeQuery } from "../../api/routeAnalysisApi.js";
import { roadLoadKey } from "../../api/roadLoadApi.js";

const period = { from: "2026-03", to: "2026-05" };

describe("basis parameters", () => {
  test("the plain typical day adds nothing", () => {
    expect(isPlain(TYPICAL)).toBe(true);
    expect(isPlain(null)).toBe(true);
    expect(appendBasis(new URLSearchParams(), TYPICAL).toString()).toBe("");
    expect(basisKey(TYPICAL)).toBe("");
  });

  test("period and hours in the API's form", () => {
    const b = { period, hours: { from: 7, to: 9 }, fixed: true };
    expect(isPlain(b)).toBe(false);
    expect(appendBasis(new URLSearchParams(), b).toString()).toBe("period=2026-03..2026-05&hours=7-9");
    expect(appendBasis(new URLSearchParams(), b, { withHours: false }).toString()).toBe("period=2026-03..2026-05");
    expect(appendBasis(new URLSearchParams(), { period: { from: "2026-04", to: "2026-04" }, hours: { from: 8, to: 8 } }).toString()).toBe(
      "period=2026-04&hours=8"
    );
    expect(basisKey(b)).toBe("p2026-03..2026-05|h7-9");
    expect(basisKey(b, { withHours: false })).toBe("p2026-03..2026-05");
    expect(basisFileTag(b)).toBe("2026-03_2026-05_h7-9");
    expect(basisFileTag({ hours: { from: 8, to: 8 } })).toBe("h8");
  });

  test("route analysis sends hours on every day type", () => {
    expect(routeQuery({ dayType: "saturday", basis: { hours: { from: 8, to: 8 } } })).toBe("date=saturday&hours=8");
    expect(routeQuery({ dayType: "weekday", basis: { period }, reach: [30] })).toBe("date=weekday&period=2026-03..2026-05&reach=30");
  });

  test("road-load keys tell bases apart and keep the old keys for the typical day", () => {
    const r = { kind: "routes", routes: ["302"], dayType: "weekday" };
    expect(roadLoadKey(r)).toBe("routes|weekday|302");
    expect(roadLoadKey({ ...r, basis: { hours: { from: 8, to: 8 } } })).toBe("routes|weekday|302|h8");
    expect(roadLoadKey({ ...r, basis: { hours: { from: 9, to: 9 } } })).not.toBe(roadLoadKey({ ...r, basis: { hours: { from: 8, to: 8 } } }));
  });
});

describe("hours", () => {
  test("move by one keeping the width, not past the day", () => {
    expect(shiftHours({ from: 7, to: 9 }, 1)).toEqual({ from: 8, to: 10 });
    expect(shiftHours({ from: 0, to: 2 }, -1)).toBeNull();
    expect(shiftHours({ from: 21, to: 23 }, 1)).toBeNull();
    expect(shiftHours(null, 1)).toBeNull();
  });

  test("changing one end keeps from ≤ to", () => {
    expect(setHoursEnd(null, "from", 8)).toEqual({ from: 8, to: 8 });
    expect(setHoursEnd({ from: 7, to: 9 }, "from", 11)).toEqual({ from: 11, to: 11 });
    expect(setHoursEnd({ from: 7, to: 9 }, "to", 5)).toEqual({ from: 5, to: 5 });
    expect(setHoursEnd({ from: 7, to: 9 }, "to", 12)).toEqual({ from: 7, to: 12 });
  });

  test("shown as clock ranges", () => {
    expect(hoursRange({ from: 7, to: 9 })).toBe("07–10");
    expect(hoursRange({ from: 23, to: 23 })).toBe("23–24");
  });
});

describe("months", () => {
  test("lists, adds and suggests months", () => {
    expect(monthsList("2025-11", "2026-02")).toEqual(["2025-11", "2025-12", "2026-01", "2026-02"]);
    expect(addMonths("2026-01", -1)).toBe("2025-12");
    expect(addMonths("2025-12", 13)).toBe("2027-01");
    expect(defaultPeriod({ first: "2023-10", last: "2026-09" })).toEqual({ from: "2026-07", to: "2026-09" });
    expect(defaultPeriod({ first: "2026-09", last: "2026-09" })).toEqual({ from: "2026-09", to: "2026-09" });
  });

  test("keeps a period inside the data, forward, and at most twelve months", () => {
    const range = { first: "2023-10", last: "2026-09" };
    expect(clampPeriod({ from: "2026-05", to: "2026-03" }, range, "to")).toEqual({ from: "2026-03", to: "2026-03" });
    expect(clampPeriod({ from: "2026-05", to: "2026-03" }, range, "from")).toEqual({ from: "2026-05", to: "2026-05" });
    expect(clampPeriod({ from: "2024-01", to: "2026-03" }, range, "to")).toEqual({ from: "2025-04", to: "2026-03" });
    expect(clampPeriod({ from: "2024-01", to: "2026-03" }, range, "from")).toEqual({ from: "2024-01", to: "2024-12" });
    expect(clampPeriod({ from: "2023-01", to: "2026-12" }, range, "to")).toEqual({ from: "2025-10", to: "2026-09" });
  });
});

describe("fixed scale", () => {
  test("only with hours, the switch on and a legendMax", () => {
    const result = { legendMax: 450 };
    expect(fixedScaleMax(result, { hours: { from: 8, to: 8 }, fixed: true })).toBe(450);
    expect(fixedScaleMax(result, { hours: { from: 7, to: 9 }, fixed: true })).toBe(1350);
    expect(fixedScaleMax(result, { hours: { from: 8, to: 8 }, fixed: false })).toBeNull();
    expect(fixedScaleMax(result, { hours: null, fixed: true })).toBeNull();
    expect(fixedScaleMax({ legendMax: null }, { hours: { from: 8, to: 8 }, fixed: true })).toBeNull();
  });

  test("log-spaced breaks rising to the top", () => {
    const b = fixedBreaks(1000, 10, 10);
    expect(b).toHaveLength(9);
    for (let i = 1; i < b.length; i += 1) expect(b[i]).toBeGreaterThan(b[i - 1]);
    expect(b[0]).toBeGreaterThan(10);
    expect(b[8]).toBeLessThan(1000);
    expect(fixedBreaks(0)).toEqual([]);
  });

  test("503 and PERIOD_UNAVAILABLE are recognised", () => {
    expect(isPeriodUnavailable("Failed to fetch bus riders on the road: 503")).toBe(true);
    expect(isPeriodUnavailable("PERIOD_UNAVAILABLE: period and hourly views ...")).toBe(true);
    expect(isPeriodUnavailable("Failed: 500")).toBe(false);
    expect(isPeriodUnavailable("")).toBe(false);
  });
});
