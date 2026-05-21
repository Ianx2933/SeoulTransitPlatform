package com.ian.transit.map.dto;

/**
 * Route-level demand inside a selected node or catchment.
 */
public record NodeRouteDemandResponse(
        String mode,
        String serviceId,
        long boarding,
        long alighting,
        long total
) {
}
