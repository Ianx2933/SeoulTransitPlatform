package com.ian.transit.congestion.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.ian.transit.congestion.model.CongestionLevel;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the stateless congestion rules documented by
 * {@link OccupancyCalculator}.
 */
class OccupancyCalculatorTest {

    private final OccupancyCalculator calculator = new OccupancyCalculator();

    @Test
    void relativeCongestionReturnsZeroWhenMaximumIsNotPositive() {
        assertEquals(0.0, calculator.calculateRelativeCongestion(10, 0));
        assertEquals(0.0, calculator.calculateRelativeCongestion(10, -1));
    }

    @Test
    void relativeCongestionIsRoundedToOneDecimalPlace() {
        assertEquals(33.3, calculator.calculateRelativeCongestion(1, 3));
        assertEquals(66.7, calculator.calculateRelativeCongestion(2, 3));
    }

    @Test
    void classificationBoundariesAreStable() {
        assertEquals(CongestionLevel.COMFORTABLE, calculator.classify(19.9));
        assertEquals(CongestionLevel.LIGHT, calculator.classify(20.0));
        assertEquals(CongestionLevel.LIGHT, calculator.classify(39.9));
        assertEquals(CongestionLevel.NORMAL, calculator.classify(40.0));
        assertEquals(CongestionLevel.NORMAL, calculator.classify(59.9));
        assertEquals(CongestionLevel.CROWDED, calculator.classify(60.0));
        assertEquals(CongestionLevel.CROWDED, calculator.classify(79.9));
        assertEquals(CongestionLevel.VERY_CROWDED, calculator.classify(80.0));
    }

    @Test
    void gradientEndpointsAreGreenAndRed() {
        assertEquals("#00FF00", calculator.gradientColor(0.0));
        assertEquals("#FF0000", calculator.gradientColor(100.0));
    }

    @Test
    void gradientClampsValuesOutsideExpectedRange() {
        assertEquals("#00FF00", calculator.gradientColor(-20.0));
        assertEquals("#FF0000", calculator.gradientColor(150.0));
    }
}
