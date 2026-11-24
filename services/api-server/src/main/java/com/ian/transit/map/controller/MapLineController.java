package com.ian.transit.map.controller;

import com.ian.transit.map.repository.MapDemandRepository;
import java.time.Duration;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for line metadata APIs.
 */
@RestController
@RequestMapping("/api/map")
public class MapLineController {

    private final MapDemandRepository mapDemandRepository;

    public MapLineController(MapDemandRepository mapDemandRepository) {
        this.mapDemandRepository = mapDemandRepository;
    }

    /**
     * The route list changes at most once a week, so browsers keep it for an
     * hour and the Firebase Hosting CDN for six; most visitors then never reach
     * Cloud Run for it. The DISTINCT over the demand table behind it takes
     * several seconds on db-f1-micro.
     */
    static final CacheControl LINES_CACHE = CacheControl.maxAge(Duration.ofHours(1))
            .sMaxAge(Duration.ofHours(6))
            .cachePublic();

    /**
     * Returns available line names for a transport mode.
     */
    @GetMapping("/lines")
    public ResponseEntity<List<String>> getLines(
            @RequestParam String mode
    ) {
        return ResponseEntity.ok()
                .cacheControl(LINES_CACHE)
                .body(mapDemandRepository.findLinesByMode(mode));
    }
}
