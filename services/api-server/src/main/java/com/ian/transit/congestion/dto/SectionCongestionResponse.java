package com.ian.transit.congestion.dto;

import java.util.List;

/**
 * Public API response DTO for section congestion.
 * 
 * This response groups stop-level congestion rows between two user-selected stops
 * so that the UI can show corridor-level bottlenecks.
 */

public record SectionCongestionResponse(
        String routeName,
        String date,
        String from,
        String to,
        List<CongestionResponse> stops
) {
}
