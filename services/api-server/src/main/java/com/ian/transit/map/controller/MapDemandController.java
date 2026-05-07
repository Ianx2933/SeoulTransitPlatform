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
 * This controller exposes APIs used by the React Leaflet frontend.
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
     */
    @GetMapping("/demand")
    public List<MapDemandResponse> getMapDemand(
            @RequestParam String mode,
            @RequestParam String dayType,
            @RequestParam Integer hour,
            @RequestParam(required = false) String line
    ) {
        return mapDemandService.getMapDemand(
                mode,
                dayType,
                hour,
                line
        );
    }
}
