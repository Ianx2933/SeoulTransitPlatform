package com.ian.transit.period;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoursTest {

    @Test
    void readsOneHourARangeOrTheOlderHourParameter() {
        assertThat(Hours.parse("8", null)).isEqualTo(new Hours(8, 8));
        assertThat(Hours.parse(" 7-9 ", null)).isEqualTo(new Hours(7, 9));
        assertThat(Hours.parse(null, 18)).isEqualTo(new Hours(18, 18));
        assertThat(Hours.parse("7-9", 18)).isEqualTo(new Hours(7, 9));      // hours wins
        assertThat(Hours.parse(null, null)).isNull();
        assertThat(new Hours(7, 9)).hasToString("7-9");
        assertThat(new Hours(8, 8).single()).isTrue();
    }

    @Test
    void refusesOutOfRangeBackwardsAndText() {
        assertThatThrownBy(() -> Hours.parse("24", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hours.parse("9-7", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hours.parse("morning", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Hours.parse(null, -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shareAddsTheHoursOfAProfile() {
        double[] p = new double[24];
        p[7] = 0.1;
        p[8] = 0.2;
        p[9] = 0.05;
        assertThat(new Hours(7, 9).share(p)).isCloseTo(0.35, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(new Hours(0, 23).share(p)).isCloseTo(0.35, org.assertj.core.data.Offset.offset(1e-12));
        assertThat(new Hours(10, 10).share(p)).isZero();
    }
}
