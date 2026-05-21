package com.ian.transit.map.controller;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.dto.NodeCatchmentDemandResponse;
import com.ian.transit.map.dto.NodeDemandDetailResponse;
import com.ian.transit.map.service.MapDemandService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for map-based transit demand.
 *
 * This controller stays thin and delegates parsing, validation, and query logic
 * to the service and repository layers.
 */
@RestController
@RequestMapping("/api/map")
public class MapDemandController {

    private final MapDemandService mapDemandService;

    public MapDemandController(MapDemandService mapDemandService) {
        this.mapDemandService = mapDemandService;
    }

    /**
     * Returns route-selected transit demand points for map rendering.
     */
    @GetMapping("/demand")
    public List<MapDemandResponse> getMapDemand(
            @RequestParam String mode,
            @RequestParam(required = false) String dayType,
            @RequestParam(required = false) String dayTypes,
            @RequestParam(required = false, defaultValue = "average") String dayAggregation,
            @RequestParam(required = false) Integer hour,
            @RequestParam(required = false) String line,
            @RequestParam(required = false) String hours,
            @RequestParam(required = false) String lines
    ) {
        return mapDemandService.getMapDemand(
                mode,
                dayType,
                dayTypes,
                dayAggregation,
                hour,
                line,
                hours,
                lines
        );
    }

    /**
     * Returns all-route demand for one selected stop or station.
     *
     * This endpoint is node-centered rather than selected-route-centered.
     */
    @GetMapping("/node-detail")
    public NodeDemandDetailResponse getNodeDemandDetail(
            @RequestParam String mode,
            @RequestParam String nodeId,
            @RequestParam(required = false) String dayType,
            @RequestParam(required = false) String dayTypes,
            @RequestParam(required = false, defaultValue = "average") String dayAggregation,
            @RequestParam(required = false) Integer hour,
            @RequestParam(required = false) String hours
    ) {
        return mapDemandService.getNodeDemandDetail(
                mode,
                nodeId,
                dayType,
                dayTypes,
                dayAggregation,
                hour,
                hours
        );
    }

    /**
     * Returns all-route demand around a selected coordinate.
     * Supported radius values are 400m, 800m, and 1000m.
     */
    @GetMapping("/node-catchment")
    public NodeCatchmentDemandResponse getNodeCatchmentDemand(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestParam Integer radiusMeters,
            @RequestParam(required = false, defaultValue = "subway,bus") String modes,
            @RequestParam(required = false) String dayType,
            @RequestParam(required = false) String dayTypes,
            @RequestParam(required = false, defaultValue = "average") String dayAggregation,
            @RequestParam(required = false) Integer hour,
            @RequestParam(required = false) String hours
    ) {
        return mapDemandService.getNodeCatchmentDemand(
                lat,
                lng,
                radiusMeters,
                modes,
                dayType,
                dayTypes,
                dayAggregation,
                hour,
                hours
        );
    }
}
