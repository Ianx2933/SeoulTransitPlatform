package com.ian.transit.map.repository.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NodeIdNormalizer}.
 * (NodeIdNormalizer 단위 테스트입니다.)
 */
class NodeIdNormalizerTest {

    @Nested
    @DisplayName("bus-style numeric node IDs")
    class BusNumericIds {

        @Test
        @DisplayName("pads a four-digit ARS number to five digits")
        void padsFourDigitArsToFive() {
            assertEquals("07616", NodeIdNormalizer.normalize("7616"));
        }

        @Test
        @DisplayName("keeps an already-padded ARS number unchanged in width")
        void keepsAlreadyPaddedArs() {
            assertEquals("07616", NodeIdNormalizer.normalize("07616"));
        }

        @Test
        @DisplayName("pads a single-digit ID to five digits")
        void padsSingleDigit() {
            assertEquals("00007", NodeIdNormalizer.normalize("7"));
        }

        @Test
        @DisplayName("trims surrounding whitespace before padding")
        void trimsWhitespace() {
            assertEquals("07616", NodeIdNormalizer.normalize("  7616  "));
        }
    }

    @Nested
    @DisplayName("subway-style non-numeric node IDs")
    class SubwayIds {

        @Test
        @DisplayName("leaves Korean station names unchanged")
        void leavesKoreanStationNamesUnchanged() {
            assertEquals("강남", NodeIdNormalizer.normalize("강남"));
        }

        @Test
        @DisplayName("leaves mixed alphanumeric station codes unchanged")
        void leavesMixedCodesUnchanged() {
            assertEquals("K123", NodeIdNormalizer.normalize("K123"));
        }
    }

    @Nested
    @DisplayName("edge cases")
    class EdgeCases {

        @Test
        @DisplayName("returns empty string for null input")
        void returnsEmptyForNull() {
            assertEquals("", NodeIdNormalizer.normalize(null));
        }

        @Test
        @DisplayName("returns empty string for blank-only input")
        void returnsEmptyForBlank() {
            assertEquals("", NodeIdNormalizer.normalize("   "));
        }

        @Test
        @DisplayName("returns long numeric IDs unchanged without overflow")
        void returnsLongNumericIdsUnchanged() {
            // 15 digits — would overflow Integer.parseInt; must be safe here.
            String longDigits = "123456789012345";
            assertEquals(longDigits, NodeIdNormalizer.normalize(longDigits));
        }

        @Test
        @DisplayName("returns a numeric ID at the exact 5-digit width unchanged")
        void returnsFiveDigitIdUnchanged() {
            assertEquals("12345", NodeIdNormalizer.normalize("12345"));
        }
    }
}
