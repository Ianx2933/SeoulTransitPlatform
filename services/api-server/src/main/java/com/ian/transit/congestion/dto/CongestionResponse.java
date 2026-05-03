package com.ian.transit.congestion.dto;

/**
 * Public API response DTO for route-stop congestion.
 * 
 * occupancy means estimated average in-vehicle passengers per service hour.
 * 
 * relativeCongestion is normalised against the maximum occupancy within the same route.
 */

public record CongestionResponse(
        String routeName,
        int sequence,
        String ars,
        String stopName,
        int occupancy,
        int maxOccupancy,
        double relativeCongestion,
        String congestionLevel,
        String congestionColor,
        Double latitude,
        Double longitude,
        int totalPassengers
) {
}
