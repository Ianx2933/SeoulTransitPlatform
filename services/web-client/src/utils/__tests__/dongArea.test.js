import { describe, expect, test } from "vitest";

import { areaCode } from "../../api/dongOdApi.js";
import { guName } from "../seoulGu.js";

describe("district areas", () => {
  test("turns a district code into its area code", () => {
    expect(areaCode("11010530", "dong")).toBe("11010530");
    expect(areaCode("11010530", "gu")).toBe("11010");
    expect(areaCode("11010530", "city")).toBe("11");
  });

  test("names Seoul gu in both languages and nothing outside Seoul", () => {
    expect(guName("11230", "ko")).toBe("강남구");
    expect(guName("11230510", "en")).toBe("Gangnam-gu");
    expect(guName("41135", "ko")).toBeNull();
  });
});
