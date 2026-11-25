package com.ian.transit.curatedod.api;

import com.ian.transit.curatedod.application.RouteHistoryService;
import com.ian.transit.curatedod.application.RouteHistoryService.History;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * GET /api/curated-od/routes/{routeName}/history: when the route changed
 * (stops added and removed), from the Seoul route master snapshots. Empty
 * entries until the snapshots are loaded.
 */
@RestController
@RequiredArgsConstructor
public class RouteHistoryController {

    static final CacheControl CACHE = CacheControl.maxAge(Duration.ofHours(1))
            .sMaxAge(Duration.ofHours(6))
            .cachePublic();

    private final RouteHistoryService service;

    @GetMapping("/api/curated-od/routes/{routeName}/history")
    public ResponseEntity<History> history(@PathVariable("routeName") String routeName) {
        return ResponseEntity.ok().cacheControl(CACHE).body(service.history(routeName));
    }
}
