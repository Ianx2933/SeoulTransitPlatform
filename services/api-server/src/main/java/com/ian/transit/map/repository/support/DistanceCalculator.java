package com.ian.transit.map.repository.support;

/**
 * Distance utility for coordinate-based map analysis.
 */
public final class DistanceCalculator {

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private DistanceCalculator() {
    }

    /**
     * Calculates distance between two coordinates with the haversine formula.
     */
    public static double calculateDistanceMeters(
            double firstLat,
            double firstLng,
            double secondLat,
            double secondLng
    ) {
        double firstLatRadians = toRadians(firstLat);
        double secondLatRadians = toRadians(secondLat);
        double deltaLat = toRadians(secondLat - firstLat);
        double deltaLng = toRadians(secondLng - firstLng);

        double haversine =
                Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(firstLatRadians) *
                        Math.cos(secondLatRadians) *
                        Math.sin(deltaLng / 2) *
                        Math.sin(deltaLng / 2);

        return EARTH_RADIUS_METERS * 2 * Math.atan2(
                Math.sqrt(haversine),
                Math.sqrt(1 - haversine)
        );
    }

    /**
     * Converts degrees to radians.
     */
    private static double toRadians(double value) {
        return (value * Math.PI) / 180;
    }
}
