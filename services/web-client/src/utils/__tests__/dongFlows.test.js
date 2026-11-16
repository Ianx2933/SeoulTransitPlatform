import { describe, expect, test } from "vitest";

import { flowsFor, shade, shadeValues, topRows } from "../dongFlows.js";

const data = {
  districts: [
    { code: "D1", name: "가동", boardings: 18, alightings: 3 },
    { code: "D2", name: "나동", boardings: 0, alightings: 15 }
  ],
  flows: [
    { fromCode: "D1", toCode: "D2", passengers: 15 },
    { fromCode: "D1", toCode: "D1", passengers: 3 }
  ]
};

describe("dongFlows", () => {
  test("reads flows from and to a district", () => {
    expect([...flowsFor(data.flows, "D1", "from")]).toEqual([["D2", 15], ["D1", 3]]);
    expect([...flowsFor(data.flows, "D2", "to")]).toEqual([["D1", 15]]);
  });

  test("shades by activity until a district is chosen", () => {
    expect(shadeValues(data, null, "from").get("D1")).toBe(21);
    expect(shadeValues(data, "D1", "from").get("D2")).toBe(15);
    expect(shade(0, 10)).toBe(0.08);
    expect(shade(10, 10)).toBeCloseTo(0.75);
  });

  test("lists the largest flows with their share", () => {
    const rows = topRows(flowsFor(data.flows, "D1", "from"), data.districts);
    expect(rows[0]).toMatchObject({ code: "D2", name: "나동", passengers: 15 });
    expect(rows[0].share).toBeCloseTo(15 / 18);
  });
});
