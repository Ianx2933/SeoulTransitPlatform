package com.ian.transit.curatedod.api;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.curatedod.application.RouteAnalysisService;
import com.ian.transit.curatedod.dto.RouteDongOdResponse;
import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Route analysis on the typical-day OD: every figure is aggregated on the
 * server from all of the route's OD pairs, so the client never needs the raw
 * matrix (which the matrix endpoint caps at 1,000 rows).
 *
 *   GET /api/curated-od/routes/{route}/stops?date=weekday
 *   GET /api/curated-od/routes/{route}/stops/{seq}?date=weekday
 *   GET /api/curated-od/routes/{route}/segment?from=5&to=22&date=weekday
 *   GET /api/curated-od/routes/{route}/dong-od?date=weekday
 *
 * Every endpoint takes an optional hour (0-23): the riders who boarded in that
 * hour of the typical day, from estimated_hourly_od (weekdays only). The stop
 * endpoint takes optional reach levels, e.g. reach=30 or reach=30,80; without
 * them it reports 25, 50, 100 and 200.
 */
@RestController
@RequestMapping("/api/curated-od/routes/{routeName}")
@RequiredArgsConstructor
public class RouteAnalysisController {

    private final RouteAnalysisService service;

    @GetMapping("/stops")
    public ResponseEntity<RouteStopsResponse> stops(
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour
    ) {
        return ResponseEntity.ok(service.stops(TypicalDay.label(기준일자), 노선명, hour));
    }

    @GetMapping("/stops/{seq}")
    public ResponseEntity<RouteStopDetailResponse> stop(
        @PathVariable("routeName") String 노선명,
        @PathVariable("seq") int seq,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour,
        @RequestParam(value = "reach", required = false) List<Double> reach
    ) {
        return ResponseEntity.ok(service.stop(TypicalDay.label(기준일자), 노선명, hour, seq, reach));
    }

    @GetMapping("/segment")
    public ResponseEntity<RouteSegmentResponse> segment(
        @PathVariable("routeName") String 노선명,
        @RequestParam("from") int from,
        @RequestParam("to") int to,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour
    ) {
        return ResponseEntity.ok(service.segment(TypicalDay.label(기준일자), 노선명, hour, from, to));
    }

    /** The route's OD grouped by administrative district (행정동), with boundaries. */
    @GetMapping("/dong-od")
    public ResponseEntity<RouteDongOdResponse> dongOd(
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour
    ) {
        return ResponseEntity.ok(service.dongOd(TypicalDay.label(기준일자), 노선명, hour));
    }
}
