package com.ian.transit.roadload.api;

import com.ian.transit.period.OdBasis;
import com.ian.transit.roadload.application.RoadLoadService;
import com.ian.transit.roadload.dto.RoadLoadResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bus riders on the road on the typical day:
 *
 *   GET /api/road-load/routes?routes=302,440&date=weekday      chosen routes, added up
 *   GET /api/road-load/stop?ars=01001&direction=board|alight   riders of one stop, all routes
 *   GET /api/road-load/segment?from=01001&to=01002             riders passing one segment
 *
 * Each takes period=2026-03..2026-05 (months, BigQuery) instead of the typical
 * day, and hours=8 or hours=7-9 (boarding hours, any day type).
 */
@RestController
@RequestMapping("/api/road-load")
@RequiredArgsConstructor
public class RoadLoadController {

    private final RoadLoadService service;

    @GetMapping("/routes")
    public ResponseEntity<RoadLoadResponse> routes(
        @RequestParam("routes") String routes,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "period", required = false) String period,
        @RequestParam(value = "hours", required = false) String hours
    ) {
        return ResponseEntity.ok(service.routes(OdBasis.of(date, period, hours, null), routes));
    }

    @GetMapping("/stop")
    public ResponseEntity<RoadLoadResponse> stop(
        @RequestParam("ars") String ars,
        @RequestParam(value = "direction", required = false) String direction,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "period", required = false) String period,
        @RequestParam(value = "hours", required = false) String hours
    ) {
        return ResponseEntity.ok(service.stop(OdBasis.of(date, period, hours, null), ars, direction));
    }

    @GetMapping("/segment")
    public ResponseEntity<RoadLoadResponse> segment(
        @RequestParam("from") String from,
        @RequestParam("to") String to,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "period", required = false) String period,
        @RequestParam(value = "hours", required = false) String hours
    ) {
        return ResponseEntity.ok(service.segment(OdBasis.of(date, period, hours, null), from, to));
    }
}
