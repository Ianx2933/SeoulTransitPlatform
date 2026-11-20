package com.ian.transit.dongod.api;

import com.ian.transit.dongod.application.DongOdService;
import com.ian.transit.dongod.application.DongRouteLoadService;
import com.ian.transit.period.OdBasis;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import com.ian.transit.dongod.dto.DongReachResponse;
import com.ian.transit.dongod.dto.DongRoutesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * District OD on the typical day by rail and bus, from TOPIS district OD, for
 * a 행정동 (8-digit code), a 구 (5-digit code, level=gu) or Seoul (code 11,
 * level=city).
 *
 *   GET /api/dong-od/summary?date=weekday&level=dong|gu|city
 *   GET /api/dong-od/{code}?date=weekday&level=dong&dest=dong|gu&direction=from|to&limit=15
 *   GET /api/dong-od/{code}/routes?date=weekday&level=dong|gu
 *   GET /api/dong-od/{code}/route-load?date=weekday&level=dong|gu&direction=od|do|both
 *   GET /api/dong-od/{code}/reach?date=weekday&level=dong|gu|city|group&direction=from|to
 *
 * level=group takes 2 to 20 행정동 codes joined by commas as {code}.
 *
 * Every endpoint takes period=2026-03..2026-05 (months, BigQuery) instead of
 * the typical day. routes and route-load also take hours=8 or hours=7-9
 * (boarding hours, any day type; with hours, route-load adds legendMax for a
 * colour scale fixed over the day). The district OD endpoints (summary,
 * {code}, reach) have no hours: TOPIS district OD is a day's total.
 */
@RestController
@RequestMapping("/api/dong-od")
@RequiredArgsConstructor
public class DongOdController {

    private final DongOdService service;
    private final DongRouteLoadService routeLoadService;

    @GetMapping("/summary")
    public ResponseEntity<List<DongOdSummaryRow>> summary(
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level,
        @RequestParam(value = "period", required = false) String period
    ) {
        return ResponseEntity.ok(service.summary(OdBasis.of(date, period, null, null), level));
    }

    @GetMapping("/{code}")
    public ResponseEntity<DongOdResponse> flows(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level,
        @RequestParam(value = "dest", required = false) String dest,
        @RequestParam(value = "direction", required = false) String direction,
        @RequestParam(value = "limit", defaultValue = "15") int limit,
        @RequestParam(value = "period", required = false) String period
    ) {
        return ResponseEntity.ok(service.flows(OdBasis.of(date, period, null, null), level, code, dest, direction, limit));
    }

    @GetMapping("/{code}/routes")
    public ResponseEntity<DongRoutesResponse> routes(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level,
        @RequestParam(value = "period", required = false) String period,
        @RequestParam(value = "hours", required = false) String hours
    ) {
        return ResponseEntity.ok(service.routes(OdBasis.of(date, period, hours, null), level, code));
    }

    @GetMapping("/{code}/route-load")
    public ResponseEntity<DongRouteLoadResponse> routeLoad(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level,
        @RequestParam(value = "direction", required = false) String direction,
        @RequestParam(value = "period", required = false) String period,
        @RequestParam(value = "hours", required = false) String hours
    ) {
        return ResponseEntity.ok(routeLoadService.load(OdBasis.of(date, period, hours, null), level, code, direction));
    }

    @GetMapping("/{code}/reach")
    public ResponseEntity<DongReachResponse> reach(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level,
        @RequestParam(value = "direction", required = false) String direction,
        @RequestParam(value = "period", required = false) String period
    ) {
        return ResponseEntity.ok(service.reach(OdBasis.of(date, period, null, null), level, code, direction));
    }
}
