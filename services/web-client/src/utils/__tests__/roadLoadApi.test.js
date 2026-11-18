import { describe, expect, test } from "vitest";

import { roadLoadKey } from "../../api/roadLoadApi.js";

describe("roadLoadKey", () => {
  test("names every request uniquely, with the day type", () => {
    expect(roadLoadKey(null)).toBe("");
    expect(roadLoadKey({ kind: "routes", routes: ["302", "440"], dayType: "saturday" })).toBe("routes|saturday|302,440");
    expect(roadLoadKey({ kind: "stop", ars: "01001" })).toBe("stop|weekday|01001|board");
    expect(roadLoadKey({ kind: "stop", ars: "01001", direction: "alight" })).toBe("stop|weekday|01001|alight");
    expect(roadLoadKey({ kind: "segment", from: "01001", to: "01002" })).toBe("segment|weekday|01001|01002");
  });
});
