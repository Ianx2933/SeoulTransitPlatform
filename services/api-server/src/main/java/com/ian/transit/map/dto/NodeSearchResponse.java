package com.ian.transit.map.dto;

/**
 * Search result for a selectable stop or station node.
 */
public record NodeSearchResponse(
        String mode,
        String nodeId,
        String nodeName,
        double lat,
        double lng
) {
}
