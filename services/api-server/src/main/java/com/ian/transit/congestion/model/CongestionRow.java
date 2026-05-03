package com.ian.transit.congestion.model;

/**
 * Internal query model returned by CongestionQueryRepository.
 * 
 * This record separates SQL result shape from public API response shape,
 * allowing calculation and formatting to happen in the application layer.
 */

public record CongestionRow(
        String routeName,
        int sequence,
        String ars,
        String stopName,
        int occupancy,
        int maxOccupancy,
        Double latitude,
        Double longitude,
        int totalPassengers
) {
}
