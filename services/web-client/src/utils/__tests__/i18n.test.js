import { describe, expect, test } from "vitest";

import { messages } from "../../i18n/messages.js";
import { interpolate, lookup, makeTranslator, pickLanguage } from "../../i18n/translate.js";

function keys(node, prefix = "") {
  return Object.entries(node).flatMap(([k, v]) =>
    v && typeof v === "object" && !Array.isArray(v) ? keys(v, `${prefix}${k}.`) : [`${prefix}${k}`]
  );
}

describe("translate", () => {
  test("looks up dotted keys and fills placeholders", () => {
    const t = makeTranslator({ a: { b: "{n}명" } }, {});
    expect(t("a.b", { n: 30 })).toBe("30명");
  });

  test("falls back to the fallback dictionary, then to the key", () => {
    const t = makeTranslator({}, { a: "English" });
    expect(t("a")).toBe("English");
    expect(t("missing.key")).toBe("missing.key");
  });

  test("leaves unknown placeholders in place", () => {
    expect(interpolate("{a} {b}", { a: 1 })).toBe("1 {b}");
    expect(lookup({ a: { b: 1 } }, "a.b")).toBeUndefined();
  });

  test("prefers a stored language, then English browsers, else Korean", () => {
    expect(pickLanguage("en", "ko-KR")).toBe("en");
    expect(pickLanguage(null, "en-AU")).toBe("en");
    expect(pickLanguage("fr", "fr-FR")).toBe("ko");
  });
});

describe("messages", () => {
  test("Korean and English have the same keys", () => {
    expect(keys(messages.ko).sort()).toEqual(keys(messages.en).sort());
    expect(messages.ko.method.items.length).toBe(messages.en.method.items.length);
  });

  test("Korean says 정류소, not 정류장", () => {
    expect(JSON.stringify(messages.ko)).not.toContain("정류장");
  });
});
