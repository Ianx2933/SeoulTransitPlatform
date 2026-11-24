package com.ian.transit.curatedod.api;

import com.ian.transit.curatedod.application.RouteAnalysisService;
import com.ian.transit.curatedod.application.RouteNameResolver;
import com.ian.transit.curatedod.application.RoutePathService;
import com.ian.transit.curatedod.dto.RouteDongOdResponse;
import com.ian.transit.curatedod.dto.RoutePathResponse;
import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import com.ian.transit.period.OdBasis;
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
 *   GET /api/curated-od/routes/{route}/path        road path and turnaround (Seoul bus route API)
 *
 * Every endpoint takes an optional period (period=2026-03..2026-05, whole months,
 * BigQuery) instead of the typical day of the last twelve months, and optional
 * boarding hours (hours=8 or hours=7-9; the older hour=8 still works), for any
 * day type. The stop
 * endpoint takes optional reach levels, e.g. reach=30 or reach=30,80; without
 * them it reports 25, 50, 100 and 200.
 *
 * {route} may be the name the map and the hourly boarding data use (5522A); it
 * is matched to the representative OD's name (5522A난곡) by RouteNameResolver,
 * and the responses carry the OD name.
 */
@RestController
@RequestMapping("/api/curated-od/routes/{routeName}")
@RequiredArgsConstructor
public class RouteAnalysisController {

    private final RouteAnalysisService service;
    private final RoutePathService pathService;
    private final RouteNameResolver names;

    @GetMapping("/stops")
    public ResponseEntity<RouteStopsResponse> stops(
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour,
        @RequestParam(value = "hours", required = false) String hours,
        @RequestParam(value = "period", required = false) String period
    ) {
        return ResponseEntity.ok(service.stops(OdBasis.of(기준일자, period, hours, hour), names.resolve(노선명)));
    }

    @GetMapping("/stops/{seq}")
    public ResponseEntity<RouteStopDetailResponse> stop(
        @PathVariable("routeName") String 노선명,
        @PathVariable("seq") int seq,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour,
        @RequestParam(value = "hours", required = false) String hours,
        @RequestParam(value = "period", required = false) String period,
        @RequestParam(value = "reach", required = false) List<Double> reach
    ) {
        return ResponseEntity.ok(service.stop(OdBasis.of(기준일자, period, hours, hour), names.resolve(노선명), seq, reach));
    }

    @GetMapping("/segment")
    public ResponseEntity<RouteSegmentResponse> segment(
        @PathVariable("routeName") String 노선명,
        @RequestParam("from") int from,
        @RequestParam("to") int to,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour,
        @RequestParam(value = "hours", required = false) String hours,
        @RequestParam(value = "period", required = false) String period
    ) {
        return ResponseEntity.ok(service.segment(OdBasis.of(기준일자, period, hours, hour), names.resolve(노선명), from, to));
    }

    /** The route's OD grouped by administrative district (행정동), with boundaries. */
    @GetMapping("/dong-od")
    public ResponseEntity<RouteDongOdResponse> dongOd(
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "hour", required = false) Integer hour,
        @RequestParam(value = "hours", required = false) String hours,
        @RequestParam(value = "period", required = false) String period
    ) {
        return ResponseEntity.ok(service.dongOd(OdBasis.of(기준일자, period, hours, hour), names.resolve(노선명)));
    }

    /** Road path of the route and the stop it turns at, from the Seoul bus route API (cached a day). */
    @GetMapping("/path")
    public ResponseEntity<RoutePathResponse> path(@PathVariable("routeName") String 노선명) {
        return ResponseEntity.ok(pathService.path(names.resolve(노선명)));
    }
}
