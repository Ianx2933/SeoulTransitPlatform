package com.ian.transit.curatedod.api;

import com.ian.transit.curatedod.application.RouteNameResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * GET /api/curated-od/routes: every route name the route analysis can serve,
 * as the representative OD names it (5522A난곡, 8773출근, 한강버스01), for the
 * route search box. Changes once a week, so the Firebase CDN keeps it six hours.
 */
@RestController
@RequestMapping("/api/curated-od/routes")
@RequiredArgsConstructor
public class RouteListController {

    static final CacheControl ROUTES_CACHE = CacheControl.maxAge(Duration.ofHours(1))
            .sMaxAge(Duration.ofHours(6))
            .cachePublic();

    private final RouteNameResolver names;

    @GetMapping
    public ResponseEntity<List<String>> routes() {
        return ResponseEntity.ok().cacheControl(ROUTES_CACHE).body(names.names());
    }
}
