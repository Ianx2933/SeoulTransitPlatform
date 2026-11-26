import { describe, expect, test } from "vitest";

import { fleetMetrics, fleetNumber } from "../fleet.js";

describe("fleet figures", () => {
  test("per trip, per vehicle and trips per vehicle", () => {
    expect(fleetMetrics({ total: 12000, maxOnBoard: 3000, vehicles: 20, trips: 150 })).toEqual({
      perTrip: 80,
      maxPerTrip: 20,
      perVehicle: 600,
      tripsPerVehicle: 7.5
    });
  });

  test("a missing input leaves its figures empty", () => {
    const m = fleetMetrics({ total: 12000, maxOnBoard: 3000, vehicles: null, trips: 150 });
    expect(m.perVehicle).toBeNull();
    expect(m.tripsPerVehicle).toBeNull();
    expect(m.perTrip).toBe(80);
  });

  test("inputs: positive whole numbers only", () => {
    expect(fleetNumber("1,200")).toBe(1200);
    expect(fleetNumber("0")).toBeNull();
    expect(fleetNumber("abc")).toBeNull();
    expect(fleetNumber("")).toBeNull();
    expect(fleetNumber("12.6")).toBe(13);
  });
});
