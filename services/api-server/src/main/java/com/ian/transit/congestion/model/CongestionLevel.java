package com.ian.transit.congestion.model;

/**
 * Business classification for route-relative congestion level.
 * 
 * These levels are designed for interpretation and visualisation, not for legal
 * vehicle-capacity compliance.
 */

public enum CongestionLevel {

    COMFORTABLE,
    LIGHT,
    NORMAL,
    CROWDED,
    VERY_CROWDED;

    /**
     * Converts percentage into congestion level.
     */
    public static CongestionLevel from(double percentage) {
        if (percentage < 20) {
            return COMFORTABLE;
        }
        if (percentage < 40) {
            return LIGHT;
        }
        if (percentage < 60) {
            return NORMAL;
        }
        if (percentage < 80) {
            return CROWDED;
        }
        return VERY_CROWDED;
    }
}
