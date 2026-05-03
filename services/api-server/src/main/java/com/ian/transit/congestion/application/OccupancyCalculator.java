package com.ian.transit.congestion.application;

import com.ian.transit.congestion.model.CongestionLevel;
import org.springframework.stereotype.Component;

/**
 * Calculator for congestion percentage, level, and visualisation color.
 * 
 * This class is intentionally stateless that so the congestion rules can be unit-tested independently
 * from database queries.
 */
@Component
public class OccupancyCalculator {

    /**

     * 
     * Calculates route-relative congestion as occupancy / maxOccupancy * 100.
     * 
     * This is not an absolute vehicle capacity load factor.
     * It shows where congestion is relatively concentrated within the same route.
     */
    public double calculateRelativeCongestion(int occupancy, int maxOccupancy) {
        if (maxOccupancy <= 0) {
            return 0.0;
        }

        double value = occupancy * 100.0 / maxOccupancy;
        return roundToOneDecimal(value);
    }

    /**
     * Converts relative congestion percentage to business-level classification.
     */
    public CongestionLevel classify(double relativeCongestion) {
        return CongestionLevel.from(relativeCongestion);
    }

    /**
     * Converts relative congestion into a green-to-red gradient HEX colour.
     * 
     * The colour is optimised for map visualisation so users can detect crowded sections quickly.
     */
    public String gradientColor(double relativeCongestion) {
        double ratio = clamp(relativeCongestion / 100.0, 0.0, 1.0);

        int[][] colors = {
                {0, 255, 0},     // Green
                {128, 255, 0},   // Light green
                {255, 255, 0},   // Yellow
                {255, 128, 0},   // Orange
                {255, 0, 0}      // Red
        };

        double scaled = ratio * 4;
        int index = (int) Math.min(scaled, 3);
        double weight = scaled - index;

        int red = interpolate(colors[index][0], colors[index + 1][0], weight);
        int green = interpolate(colors[index][1], colors[index + 1][1], weight);
        int blue = interpolate(colors[index][2], colors[index + 1][2], weight);

        return String.format("#%02X%02X%02X", red, green, blue);
    }

    private double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private int interpolate(int start, int end, double weight) {
        return (int) (start + (end - start) * weight);
    }
}
