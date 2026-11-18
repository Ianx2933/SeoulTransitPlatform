import { describe, expect, test } from "vitest";

import { csvFileName, roadCsv, routeStopsCsv } from "../roadCsv.js";

describe("roadCsv", () => {
  test("segments then boarding and alighting stops, quoted where needed", () => {
    const csv = roadCsv({
      segments: [
        { fromArs: "01001", fromName: "광화문", toArs: "01002", toName: "세종로", load: 12.5, routes: ["302", "440"], byRoute: { 302: 10, 440: 2.5 } }
      ],
      origins: [{ ars: "01001", name: "광화문, 정부청사", lat: 37.57, lng: 126.97, riders: 4 }],
      destinations: [{ ars: "02002", name: 'A "B"', lat: 37.5, lng: 127, riders: 3 }]
    });
    const lines = csv.trim().split("\r\n");
    expect(lines[0]).toBe("type,from_ars,from_name,to_ars,to_name,stop_ars,stop_name,lat,lng,riders,routes");
    expect(lines[1]).toBe("segment,01001,광화문,01002,세종로,,,,,12.5,302:10;440:2.5");
    expect(lines[2]).toBe('origin,,,,,01001,"광화문, 정부청사",37.57,126.97,4,');
    expect(lines[3]).toBe('destination,,,,,02002,"A ""B""",37.5,127,3,');
  });

  test("district results without stops still work", () => {
    expect(roadCsv({ segments: [] }).trim()).toBe(
      "type,from_ars,from_name,to_ars,to_name,stop_ars,stop_name,lat,lng,riders,routes"
    );
  });

  test("file names keep Hangul and drop symbols", () => {
    expect(csvFileName("road", "사직동", "both", "weekday")).toBe("road-사직동-both-weekday.csv");
    expect(csvFileName("segment", "광화문 → 세종로", null)).toBe("segment-광화문_세종로.csv");
    expect(csvFileName()).toBe("road-load.csv");
  });

  test("route stop table in stop order", () => {
    const csv = routeStopsCsv({
      stops: [
        { seq: 1, name: "기점", ars: "01001", boardings: 10, alightings: 0, onBoardAfter: 10 },
        { seq: 2, name: "다음, 정류소", ars: "01002", boardings: 2, alightings: 5, onBoardAfter: 7 }
      ]
    });
    expect(csv.trim().split("\r\n")).toEqual([
      "seq,stop_name,ars,boardings,alightings,on_board_after",
      "1,기점,01001,10,0,10",
      '2,"다음, 정류소",01002,2,5,7'
    ]);
  });
});
