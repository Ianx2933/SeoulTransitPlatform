package com.ian.transit.map.dto;

import java.util.List;

/**
 * All-route demand detail for a selected stop or station node.
 */
public record NodeDemandDetailResponse(
        String mode,
        String nodeId,
        String nodeName,
        double lat,
        double lng,
        long boarding,
        long alighting,
        long total,
        List<NodeRouteDemandResponse> routes
) {
}
