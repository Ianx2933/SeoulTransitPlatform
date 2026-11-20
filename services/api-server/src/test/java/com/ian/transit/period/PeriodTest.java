package com.ian.transit.period;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.YearMonth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PeriodTest {

    @Test
    void readsARangeOrOneMonth() {
        Period p = Period.parse("2026-03..2026-05");
        assertThat(p.from()).isEqualTo(YearMonth.of(2026, 3));
        assertThat(p.to()).isEqualTo(YearMonth.of(2026, 5));
        assertThat(p.monthCount()).isEqualTo(3);
        assertThat(p.firstMonthDay()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(p.lastMonthDay()).isEqualTo(LocalDate.of(2026, 5, 1));
        assertThat(p.lastDay()).isEqualTo(LocalDate.of(2026, 5, 31));
        assertThat(p).hasToString("2026-03..2026-05");

        Period one = Period.parse(" 2026-02 ");
        assertThat(one.from()).isEqualTo(one.to());
        assertThat(one.lastDay()).isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(one).hasToString("2026-02");
    }

    @Test
    void blankMeansTheTypicalDay() {
        assertThat(Period.parse(null)).isNull();
        assertThat(Period.parse("  ")).isNull();
    }

    @Test
    void refusesBackwardsLongAndMalformed() {
        assertThatThrownBy(() -> Period.parse("2026-05..2026-03")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Period.parse("2025-01..2026-01")).hasMessageContaining("at most 12");
        assertThat(Period.parse("2025-02..2026-01").monthCount()).isEqualTo(12);
        assertThatThrownBy(() -> Period.parse("2026-3")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Period.parse("2026-03..2026-04..2026-05")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Period.parse("20260301")).isInstanceOf(IllegalArgumentException.class);
    }
}
