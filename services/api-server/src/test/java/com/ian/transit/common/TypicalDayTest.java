package com.ian.transit.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

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
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("weekday");
        assertThatThrownBy(() -> TypicalDay.label("typical_holiday"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
