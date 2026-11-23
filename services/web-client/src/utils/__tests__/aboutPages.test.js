import { describe, expect, test } from "vitest";

import { messages } from "../../i18n/messages.js";
import { PRIVACY_SECTIONS } from "../../i18n/messagesAbout.js";
import { OPERATOR } from "../../site.js";

describe("about and privacy pages", () => {
  test("every privacy section has a title and body in both languages", () => {
    for (const lang of ["ko", "en"]) {
      for (const key of PRIVACY_SECTIONS) {
        expect(messages[lang].privacy.sections[key].title).toBeTruthy();
        expect(messages[lang].privacy.sections[key].body).toBeTruthy();
      }
    }
  });

  test("list items match across languages and the operator is Ian Lee", () => {
    expect(messages.ko.about.useItems.length).toBe(messages.en.about.useItems.length);
    expect(messages.ko.about.sourceItems.length).toBe(messages.en.about.sourceItems.length);
    expect(OPERATOR).toBe("Ian Lee");
  });

  test("no page calls the site a personal project or an official service", () => {
    const text = JSON.stringify([messages.ko.about, messages.ko.privacy, messages.en.about, messages.en.privacy]);
    expect(text).not.toMatch(/개인 프로젝트|personal project/i);
    expect(text).toContain("공식 서비스가 아니며");
    expect(text).toContain("not an official Seoul Metropolitan Government service");
  });
});
