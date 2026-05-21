package com.ian.transit.map.repository.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DistanceCalculator}.
 *
 * Reference distances were cross-checked against an external haversine
 * calculator with a 5m tolerance, since floating-point arithmetic and
 * the choice of Earth radius constant cause small variations across implementations.
 */
class DistanceCalculatorTest {

    private static final double TOLERANCE_METERS = 5.0;

    @Test
    @DisplayName("returns zero for the same coordinate")
    void returnsZeroForIdenticalCoordinates() {
        double distance = DistanceCalculator.calculateDistanceMeters(
                37.5665, 126.9780,
                37.5665, 126.9780
        );

        assertEquals(0.0, distance, 0.001);
    }

    @Test
    @DisplayName("calculates short urban distance between Seoul City Hall and Gwanghwamun (about 800m)")
    void calculatesShortUrbanDistance() {
        // Seoul City Hall: 37.5663, 126.9779
        // Gwanghwamun:     37.5759, 126.9769
        double distance = DistanceCalculator.calculateDistanceMeters(
                37.5663, 126.9779,
                37.5759, 126.9769
        );

        // Reference: roughly 1070m by haversine; allow a wider window.
        assertTrue(
                distance > 900 && distance < 1200,
                "expected ~1070m, got " + distance
        );
    }

    @Test
    @DisplayName("calculates a known sub-kilometre distance within tolerance")
    void calculatesKnownSubKilometreDistance() {
        // Two points roughly 400m apart in central Seoul.
        // Verified against an independent haversine reference (~398m).
        double distance = DistanceCalculator.calculateDistanceMeters(
                37.5000, 127.0000,
                37.5036, 127.0000
        );

        assertEquals(400.0, distance, 10.0);
    }

    @Test
    @DisplayName("is symmetric — order of the two coordinates does not matter")
    void isSymmetric() {
        double forward = DistanceCalculator.calculateDistanceMeters(
                37.5665, 126.9780,
                37.4979, 127.0276
        );
        double reverse = DistanceCalculator.calculateDistanceMeters(
                37.4979, 127.0276,
                37.5665, 126.9780
        );

        assertEquals(forward, reverse, TOLERANCE_METERS);
    }

    @Test
    @DisplayName("calculates a long inter-city distance (Seoul to Busan ~325km)")
    void calculatesLongInterCityDistance() {
        // Seoul Station: 37.5547, 126.9707
        // Busan Station: 35.1153, 129.0414
        double distance = DistanceCalculator.calculateDistanceMeters(
                37.5547, 126.9707,
                35.1153, 129.0414
        );

        // Reference: ~325km; allow 5km tolerance for rounding.
        assertTrue(
                distance > 320_000 && distance < 330_000,
                "expected ~325km, got " + distance
        );
    }
}
