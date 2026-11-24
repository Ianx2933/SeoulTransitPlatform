package com.ian.transit.map.month;

import com.ian.transit.map.dto.MapDemandResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * The demand map for one month, buses only (BigQuery):
 *
 *   GET /api/map/bus-months
 *   GET /api/map/demand-month?month=2026-03&dayTypes=mon,tue&dayAggregation=average&hours=8,9&lines=143,302
 *
 * Same response as /api/map/demand. The month's figures change only when a
 * month is reloaded, so the Firebase CDN keeps both for six hours.
 */
@RestController
@RequestMapping("/api/map")
public class BusMonthDemandController {

    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1)).sMaxAge(Duration.ofHours(6)).cachePublic();

    private final BusMonthDemandService service;

    public BusMonthDemandController(BusMonthDemandService service) {
        this.service = service;
    }

    @GetMapping("/bus-months")
    public ResponseEntity<List<String>> months() {
        return ResponseEntity.ok().cacheControl(CACHE).body(service.months());
    }

    @GetMapping("/demand-month")
    public ResponseEntity<List<MapDemandResponse>> demand(
            @RequestParam String month,
            @RequestParam String dayTypes,
            @RequestParam(required = false, defaultValue = "average") String dayAggregation,
            @RequestParam String hours,
            @RequestParam String lines
    ) {
        return ResponseEntity.ok().cacheControl(CACHE)
                .body(service.demand(month, dayTypes, dayAggregation, hours, lines));
    }
}
