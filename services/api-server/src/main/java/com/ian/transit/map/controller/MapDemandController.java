package com.ian.transit.map.controller;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.service.MapDemandService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for map-based transit demand.
 *
 * This controller is intentionally thin because parsing and validation belong
 * to the service layer.
 */
@RestController
@RequestMapping("/api/map")
public class MapDemandController {

    private final MapDemandService mapDemandService;

    public MapDemandController(MapDemandService mapDemandService) {
        this.mapDemandService = mapDemandService;
    }

    /**
     * Returns transit demand points for map rendering.
     *
     * Supported request styles:
     * - Single line/hour: /api/map/demand?mode=subway&dayType=mon&line=2호선&hour=8
     * - Multiple lines/hours: /api/map/demand?mode=subway&dayType=mon&lines=2호선,7호선&hours=7,8,9
     *
     * The multi-value parameters are comma-separated strings so the frontend
     * can build simple URLs without relying on repeated query parameters.
     */
    @GetMapping("/demand")
    public List<MapDemandResponse> getMapDemand(
            @RequestParam String mode,
            @RequestParam String dayType,
            @RequestParam(required = false) Integer hour,
            @RequestParam(required = false) String line,
            @RequestParam(required = false) String hours,
            @RequestParam(required = false) String lines
    ) {
        return mapDemandService.getMapDemand(
                mode,
                dayType,
                hour,
                line,
                hours,
                lines
        );
    }
}
