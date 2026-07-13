package com.ian.transit.map.controller;

import com.ian.transit.map.dto.DistrictDemandResponse;
import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.dto.NodeCatchmentDemandResponse;
import com.ian.transit.map.dto.NodeDemandDetailResponse;
import com.ian.transit.map.dto.NodeSearchResponse;
import com.ian.transit.map.service.MapDemandService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for map-based transit demand.
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
     * Returns selectable stop or station nodes by keyword.
     */
    @GetMapping("/nodes/search")
    public List<NodeSearchResponse> searchNodes(
            @RequestParam String keyword,
            @RequestParam(required = false, defaultValue = "30") Integer limit
    ) {
        return mapDemandService.searchNodes(keyword, limit);
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
     *
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

    /** Returns all-route demand inside selected administrative districts. */
    @GetMapping("/district-demand")
    public DistrictDemandResponse getDistrictDemand(
            @RequestParam(required = false) String districtCode,
            @RequestParam(required = false) String districtCodes,
            @RequestParam(required = false, defaultValue = "subway,bus") String modes,
            @RequestParam(required = false) String dayType,
            @RequestParam(required = false) String dayTypes,
            @RequestParam(required = false, defaultValue = "average") String dayAggregation,
            @RequestParam(required = false) Integer hour,
            @RequestParam(required = false) String hours,
            @RequestParam(required = false) Integer nodeLimit
    ) {
        return mapDemandService.getDistrictDemand(
                districtCode,
                districtCodes,
                modes,
                dayType,
                dayTypes,
                dayAggregation,
                hour,
                hours,
                nodeLimit
        );
    }

}
