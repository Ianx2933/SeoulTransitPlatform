import { describe, expect, test } from "vitest";

import { comparePeriods, compareStops, loadSummary } from "../routeCompare.js";

const range = { first: "2023-10", last: "2026-09" };

describe("comparePeriods", () => {
  test("three months up to the old snapshot, three from the new one", () => {
    expect(comparePeriods({ kind: "changed", since: "2024-01-01", date: "2024-04-01" }, range)).toEqual({
      before: { from: "2023-11", to: "2024-01" },
      after: { from: "2024-04", to: "2024-06" },
      uncertain: false
    });
  });

  test("a change seen only against today compares with the last months", () => {
    expect(comparePeriods({ kind: "today", since: "2024-04-01", date: null }, range)).toEqual({
      before: { from: "2024-02", to: "2024-04" },
      after: { from: "2026-07", to: "2026-09" },
      uncertain: true
    });
  });

  test("a snapshot newer than the OD compares with its last months, marked uncertain", () => {
    expect(comparePeriods({ kind: "changed", since: "2024-01-01", date: "2026-11-02" }, range)).toEqual({
      before: { from: "2023-11", to: "2024-01" },
      after: { from: "2026-07", to: "2026-09" },
      uncertain: true
    });
    expect(comparePeriods({ kind: "first", since: null, date: "2024-01-01" }, range)).toBeNull();
  });
});

describe("compareStops", () => {
  test("kept, added and removed stops", () => {
    const a = [
      { seq: 1, ars: "1001", name: "가", boardings: 10, onBoardAfter: 10 },
      { seq: 2, ars: "01002", name: "나", boardings: 5, onBoardAfter: 12 }
    ];
    const b = [
      { seq: 1, ars: "01001", name: "가", boardings: 14, onBoardAfter: 14 },
      { seq: 2, ars: "01009", name: "새", boardings: 3, onBoardAfter: 15 }
    ];
    const rows = compareStops(a, b);
    expect(rows.map((r) => [r.name, r.status, r.a, r.b, r.change])).toEqual([
      ["가", "both", 10, 14, 4],
      ["새", "added", null, 3, null],
      ["나", "removed", 5, null, null]
    ]);
    expect(loadSummary({ totalPassengers: 17, stops: b })).toEqual({ total: 17, maxOnBoard: 15, stops: 2 });
  });
});
