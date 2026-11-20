package com.ian.transit.period.application;

import com.ian.transit.period.Hours;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class HourlySharesTest {

    @Test
    void normalisesArsLikeEstimateHourlyOd() {
        assertThat(HourlyShares.normalizeArs("1001")).isEqualTo("01001");
        assertThat(HourlyShares.normalizeArs(" 01001 ")).isEqualTo("01001");
        assertThat(HourlyShares.normalizeArs("")).isEqualTo("00000");
        assertThat(HourlyShares.normalizeArs(null)).isEqualTo("00000");
        assertThat(HourlyShares.normalizeArs("name:여의도")).isEqualTo("name:여의도");
        assertThat(HourlyShares.splittable("1001")).isTrue();
        assertThat(HourlyShares.splittable("00000")).isFalse();
        assertThat(HourlyShares.splittable("name:여의도")).isFalse();
    }

    @Test
    void usesTheStopsProfileElseTheDefaultAndLeavesUnsplittableStopsOut() {
        double[] fitted = new double[24];
        fitted[8] = 0.5;
        fitted[9] = 0.5;
        double[] fallback = new double[24];
        java.util.Arrays.fill(fallback, 1.0 / 24);
        Map<String, double[]> profiles = Map.of("143|01001", fitted);
        Hours h = new Hours(8, 8);

        assertThat(HourlyShares.share("143", "1001", h, profiles, fallback)).isEqualTo(0.5);
        assertThat(HourlyShares.share(" 143 ", "01001", h, profiles, fallback)).isEqualTo(0.5);
        assertThat(HourlyShares.share("143", "01002", h, profiles, fallback)).isCloseTo(1.0 / 24, within(1e-12));
        assertThat(HourlyShares.share("143", "name:선착장", h, profiles, fallback)).isEqualTo(-1);
    }
}
