package com.ian.transit.terrain.api;

import com.ian.transit.terrain.application.TerrainAccessibilityService;
import com.ian.transit.terrain.dto.DongAccessibilityResponse;
import com.ian.transit.terrain.dto.StopTerrainResponse;
import com.ian.transit.terrain.dto.TerrainDatasetResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stops/accessibility")
public class TerrainAccessibilityController {
    private final TerrainAccessibilityService service;
    public TerrainAccessibilityController(TerrainAccessibilityService service) {
        this.service = service;
    }

    /** Inclusive percentage-grade threshold; stable offset pagination. */
    @GetMapping
    public ResponseEntity<List<StopTerrainResponse>> getSteepStops(
            @RequestParam(defaultValue = "8.0") double minSlope,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        return ResponseEntity.ok(service.getSteepStops(minSlope, limit, offset));
    }

    /** minStops applies to measured stops; zero permits a null-ratio row. */
    @GetMapping("/summary")
    public ResponseEntity<List<DongAccessibilityResponse>> getDongSummary(
            @RequestParam(defaultValue = "8.0") double minSlope,
            @RequestParam(defaultValue = "10") int minStops) {
        return ResponseEntity.ok(service.getDongSummary(minSlope, minStops));
    }

    @GetMapping("/metadata")
    public ResponseEntity<TerrainDatasetResponse> getMetadata() {
        return ResponseEntity.ok(service.getMetadata());
    }
}
