import { describe, expect, test } from "vitest";

import { groupByPlace, modifiers, nextPick, NO_PICK, singleSeq } from "../stopPick.js";

const all = [1, 2, 3, 4, 5, 6, 7];

describe("picking stops", () => {
  test("a click picks one", () => {
    const p = nextPick(NO_PICK, 3, {}, all);
    expect(p).toEqual({ anchor: 3, seqs: [3] });
    expect(singleSeq(p)).toBe(3);
  });

  test("Shift picks the range from the first picked stop", () => {
    const p = nextPick(nextPick(NO_PICK, 3, {}, all), 6, { shift: true }, all);
    expect(p.seqs).toEqual([3, 4, 5, 6]);
    expect(singleSeq(p)).toBeNull();
    expect(nextPick(p, 1, { shift: true }, all).seqs).toEqual([1, 2, 3]);
  });

  test("Ctrl adds and removes one at a time", () => {
    let p = nextPick(NO_PICK, 3, {}, all);
    p = nextPick(p, 6, { ctrl: true }, all);
    p = nextPick(p, 1, { ctrl: true }, all);
    expect(p.seqs).toEqual([1, 3, 6]);
    p = nextPick(p, 3, { ctrl: true }, all);
    expect(p).toEqual({ anchor: 1, seqs: [1, 6] });
  });

  test("Ctrl+Shift adds a range", () => {
    let p = nextPick(NO_PICK, 1, {}, all);
    p = nextPick(p, 6, { ctrl: true }, all);
    p = nextPick(p, 3, { ctrl: true, shift: true }, all);
    expect(p.seqs).toEqual([1, 2, 3, 6]);
  });

  test("keys from a Leaflet or a DOM event", () => {
    expect(modifiers({ originalEvent: { shiftKey: true } })).toEqual({ shift: true, ctrl: false });
    expect(modifiers({ metaKey: true })).toEqual({ shift: false, ctrl: true });
  });

  test("a terminus at both ends is one place", () => {
    const groups = groupByPlace([
      { seq: 1, lat: 37.61234, lng: 127.01111 },
      { seq: 2, lat: 37.62, lng: 127.02 },
      { seq: 9, lat: 37.612341, lng: 127.011112 }
    ]);
    expect(groups.map((g) => g.map((p) => p.seq))).toEqual([[1, 9], [2]]);
  });

  test("the same ARS or a few metres apart is one place", () => {
    const groups = groupByPlace([
      { seq: 3, ars: "22100", lat: 37.5, lng: 127.0 },
      { seq: 4, ars: "22101", lat: 37.51, lng: 127.0 },
      { seq: 20, ars: "22109", lat: 37.50015, lng: 127.0001 },
      { seq: 30, ars: "22101", lat: 37.5105, lng: 127.0 }
    ]);
    expect(groups.map((g) => g.map((p) => p.seq))).toEqual([[3, 20], [4, 30]]);
  });
});
