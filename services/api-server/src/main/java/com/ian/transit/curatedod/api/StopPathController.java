package com.ian.transit.curatedod.api;

import com.ian.transit.curatedod.application.StopPathService;
import com.ian.transit.curatedod.application.StopPathService.StopPath;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/**
 * GET /api/curated-od/stop-path?stops=01001,01002,...[&route=3411&at=2024-11-30]:
 * a line through the given stops in order, for drawing a route as it ran in a
 * past period. With route and at, the stop order of the route master snapshot
 * in force that day is used when there is one.
 * Stops and stored segments change weekly at most, so the CDN keeps it six hours.
 */
@RestController
@RequestMapping("/api/curated-od/stop-path")
@RequiredArgsConstructor
public class StopPathController {

    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1))
            .sMaxAge(Duration.ofHours(6))
            .cachePublic();

    private final StopPathService service;

    @GetMapping
    public ResponseEntity<StopPath> path(@RequestParam("stops") String stops,
                                         @RequestParam(value = "route", required = false) String route,
                                         @RequestParam(value = "at", required = false) String at) {
        LocalDate day = null;
        if (at != null && !at.isBlank()) {
            try {
                day = LocalDate.parse(at.trim());
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("at must be a day like 2024-11-30");
            }
        }
        return ResponseEntity.ok().cacheControl(CACHE).body(service.path(stops, route, day));
    }
}
