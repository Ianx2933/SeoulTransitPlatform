import { readdirSync, readFileSync } from "node:fs";
import { describe, expect, test } from "vitest";

import { koByEnglish } from "../../i18n/koByEnglish.js";

const componentsDir = new URL("../../components/", import.meta.url);

/** Every tx("...") literal used in the components. */
function txKeys() {
  const keys = new Set();
  for (const name of readdirSync(componentsDir)) {
    if (!name.endsWith(".jsx")) continue;
    const source = readFileSync(new URL(name, componentsDir), "utf8");
    for (const match of source.matchAll(/\btx\(\s*"((?:[^"\\]|\\.)*)"/g)) {
      keys.add(JSON.parse(`"${match[1]}"`));
    }
  }
  return [...keys];
}

const placeholders = (text) => [...text.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort();

describe("koByEnglish", () => {
  test("has Korean for every tx() text in the components", () => {
    const missing = txKeys().filter((key) => !(key in koByEnglish));
    expect(missing).toEqual([]);
  });

  test("keeps the same placeholders and says 정류소", () => {
    for (const [en, ko] of Object.entries(koByEnglish)) {
      expect(placeholders(ko), en).toEqual(placeholders(en));
      expect(ko, en).not.toContain("정류장");
    }
  });
});
