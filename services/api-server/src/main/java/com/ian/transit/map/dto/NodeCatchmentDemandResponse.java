package com.ian.transit.map.dto;

import java.util.List;

/**
 * All-route demand detail for a radius-based node catchment.
 */
public record NodeCatchmentDemandResponse(
        double centerLat,
        double centerLng,
        int radiusMeters,
        long boarding,
        long alighting,
        long total,
        List<NodeDemandResponse> nodes,
        List<NodeRouteDemandResponse> routes
) {
}
