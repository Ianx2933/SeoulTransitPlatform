import { afterEach, describe, expect, test, vi } from "vitest";

import { readStoredNumber, writeStoredNumber } from "../../hooks/useStoredNumber.js";

describe("stored number", () => {
  afterEach(() => vi.unstubAllGlobals());

  test("reads back what was written, falls back on junk", () => {
    const store = new Map();
    vi.stubGlobal("window", {
      localStorage: { getItem: (k) => (store.has(k) ? store.get(k) : null), setItem: (k, v) => store.set(k, v) }
    });
    expect(readStoredNumber("m", 10)).toBe(10);
    writeStoredNumber("m", 25);
    expect(readStoredNumber("m", 10)).toBe(25);
    store.set("m", "abc");
    expect(readStoredNumber("m", 10)).toBe(10);
    store.set("m", "-3");
    expect(readStoredNumber("m", 10)).toBe(10);
  });

  test("blocked storage does not throw", () => {
    vi.stubGlobal("window", {
      localStorage: {
        getItem: () => {
          throw new Error("blocked");
        },
        setItem: () => {
          throw new Error("blocked");
        }
      }
    });
    expect(readStoredNumber("m", 10)).toBe(10);
    expect(() => writeStoredNumber("m", 5)).not.toThrow();
  });
});
