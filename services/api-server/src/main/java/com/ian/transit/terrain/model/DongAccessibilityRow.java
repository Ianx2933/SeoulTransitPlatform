package com.ian.transit.terrain.model;

/** Counts are computed before rounding; unmeasured stops are not flat stops. */
public record DongAccessibilityRow(
        String admCd, String admNm, long totalStops, long measuredStops,
        long missingSlopeStops, long steepStops, Double avgSlopePct,
        Double maxSlopePct, Double avgElevM
) {}
