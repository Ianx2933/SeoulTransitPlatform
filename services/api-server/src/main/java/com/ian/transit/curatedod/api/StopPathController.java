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

/**
 * GET /api/curated-od/stop-path?stops=01001,01002,...: a line through the
 * given stops in order, for drawing a route as it ran in a past period.
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
    public ResponseEntity<StopPath> path(@RequestParam("stops") String stops) {
        return ResponseEntity.ok().cacheControl(CACHE).body(service.path(stops));
    }
}
