import { describe, expect, test } from "vitest";

import { sortFlows } from "../flowSort.js";

const flows = [
  { seq: 12, name: "다", passengers: 5 },
  { seq: 3, name: "가", passengers: 9 },
  { seq: 7, name: "나", passengers: 9 }
];

describe("sortFlows", () => {
  test("most riders first, ties by stop number", () => {
    expect(sortFlows(flows, "riders").map((f) => f.seq)).toEqual([3, 7, 12]);
  });

  test("stop number and name", () => {
    expect(sortFlows(flows, "seq").map((f) => f.seq)).toEqual([3, 7, 12]);
    expect(sortFlows(flows, "name").map((f) => f.name)).toEqual(["가", "나", "다"]);
  });

  test("does not change the input", () => {
    const copy = flows.map((f) => f.seq);
    sortFlows(flows, "seq");
    expect(flows.map((f) => f.seq)).toEqual(copy);
  });
});
