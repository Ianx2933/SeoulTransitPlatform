package com.ian.transit.map.dto;

/**
 * Node-level demand inside a selected catchment.
 */
public record NodeDemandResponse(
        String mode,
        String nodeId,
        String nodeName,
        double lat,
        double lng,
        double distanceMeters,
        long boarding,
        long alighting,
        long total
) {
}
