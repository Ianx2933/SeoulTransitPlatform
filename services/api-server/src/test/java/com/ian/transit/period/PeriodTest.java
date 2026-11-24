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
    void readsOneTopisDay() {
        Period d = Period.parse("2026-09-15");
        assertThat(d.oneDay()).isTrue();
        assertThat(d.from()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(d.to()).isEqualTo(YearMonth.of(2026, 9));
        assertThat(d.monthCount()).isEqualTo(1);
        assertThat(d.firstMonthDay()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(d.firstDay()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(d.lastDay()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(d).hasToString("2026-09-15");
        assertThat(Period.parse("2026-09").oneDay()).isFalse();
        assertThat(Period.parse("2026-09").firstDay()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThatThrownBy(() -> Period.parse("2026-02-30")).hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> new Period(YearMonth.of(2026, 8), YearMonth.of(2026, 9), LocalDate.of(2026, 9, 1)))
                .isInstanceOf(IllegalArgumentException.class);
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
