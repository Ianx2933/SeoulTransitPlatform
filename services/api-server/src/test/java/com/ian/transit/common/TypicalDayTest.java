package com.ian.transit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TypicalDayTest {

    @Test
    void missingDateMeansTheTypicalWeekday() {
        assertThat(TypicalDay.label(null)).isEqualTo("typical_weekday");
        assertThat(TypicalDay.label(" ")).isEqualTo("typical_weekday");
    }

    @Test
    void dayTypesAreAcceptedWithOrWithoutThePrefix() {
        assertThat(TypicalDay.label("saturday")).isEqualTo("typical_saturday");
        assertThat(TypicalDay.label("typical_sunday_holiday")).isEqualTo("typical_sunday_holiday");
        assertThat(TypicalDay.label(" weekday ")).isEqualTo("typical_weekday");
    }

    @Test
    void aDatedDayIsRefusedNotAnsweredEmpty() {
        assertThatThrownBy(() -> TypicalDay.label("20251111"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weekday");
        assertThatThrownBy(() -> TypicalDay.label("typical_holiday"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passengerFiguresAreRoundedToTwoDecimals() {
        assertThat(TypicalDay.round(40050.70000000004)).isEqualTo(40050.7);
        assertThat(TypicalDay.round(1.0 / 12)).isEqualTo(0.08);
        assertThat(TypicalDay.round(null)).isNull();
    }
}
