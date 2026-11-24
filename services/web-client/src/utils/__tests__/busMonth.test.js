import { describe, expect, test } from "vitest";

import { busMonthQuery } from "../../api/mapDemandApi.js";

describe("busMonthQuery", () => {
  test("sends the month with the map's filters as comma lists", () => {
    const q = new URLSearchParams(
      busMonthQuery({ month: "2026-03", lines: ["143", "302"], dayTypes: ["mon", "tue"], dayAggregation: "sum", hours: [8, 9] })
    );
    expect(q.get("month")).toBe("2026-03");
    expect(q.get("lines")).toBe("143,302");
    expect(q.get("dayTypes")).toBe("mon,tue");
    expect(q.get("dayAggregation")).toBe("sum");
    expect(q.get("hours")).toBe("8,9");
  });
});
