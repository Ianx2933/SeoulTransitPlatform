package com.ian.transit.map.dto;

/**
 * Response DTO for map demand visualization.
 */
public record MapDemandResponse(
        String mode,
        String serviceId,
        String nodeId,
        String nodeName,
        Double lat,
        Double lng,
        Long boarding,
        Long alighting
) {
}
