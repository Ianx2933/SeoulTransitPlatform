package com.ian.transit.period;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OdBasisTest {

    @Test
    void defaultsToThePlainTypicalWeekday() {
        OdBasis b = OdBasis.of(null, null, null, null);
        assertThat(b.dayType()).isEqualTo("weekday");
        assertThat(b.label()).isEqualTo("typical_weekday");
        assertThat(b.plain()).isTrue();
        assertThat(b.typical()).isTrue();
    }

    @Test
    void periodAndHoursMakeItNotPlain() {
        OdBasis b = OdBasis.of("typical_saturday", "2026-03..2026-05", "7-9", null);
        assertThat(b.dayType()).isEqualTo("saturday");
        assertThat(b.typical()).isFalse();
        assertThat(b.plain()).isFalse();
        assertThat(b).hasToString("saturday 2026-03..2026-05 7-9h");
        assertThat(OdBasis.of("weekday", null, null, 8).plain()).isFalse();
    }

    @Test
    void stillRefusesADatedDay() {
        assertThatThrownBy(() -> OdBasis.of("20251111", null, null, null)).isInstanceOf(IllegalArgumentException.class);
    }
}
