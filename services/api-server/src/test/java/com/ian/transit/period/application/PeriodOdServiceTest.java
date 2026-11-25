package com.ian.transit.period.application;

import com.ian.transit.dongod.application.RouteLoadCalculator;
import com.ian.transit.period.Hours;
import com.ian.transit.period.infrastructure.PeriodOdRepository;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PeriodOdServiceTest {

    private static List<RouteLoadCalculator.Leg> split(PeriodOdService s, List<RouteLoadCalculator.Leg> legs, Hours h) {
        return s.splitByHours(legs, "saturday", h,
                RouteLoadCalculator.Leg::route, RouteLoadCalculator.Leg::boardArs, RouteLoadCalculator.Leg::passengers,
                (l, p) -> new RouteLoadCalculator.Leg(l.route(), l.boardArs(), l.alightArs(), p, l.boardSeq(), l.alightSeq()));
    }

    @Test
    void hoursCutEveryLegByItsBoardingStopAndTheDayAddsUp() {
        PeriodOdRepository repo = mock(PeriodOdRepository.class);
        double[] fitted = new double[24];
        fitted[7] = 0.25;
        fitted[8] = 0.75;
        double[] fallback = new double[24];
        Arrays.fill(fallback, 1.0 / 24);
        when(repo.profiles(eq("saturday"), anyCollection())).thenReturn(Map.of("143|01001", fitted));
        when(repo.defaultProfile()).thenReturn(fallback);
        PeriodOdService s = new PeriodOdService(repo);

        List<RouteLoadCalculator.Leg> legs = List.of(
                new RouteLoadCalculator.Leg("143", "01001", "01005", 40, 1, 5),
                new RouteLoadCalculator.Leg("143", "01002", "01005", 24, 2, 5),     // no profile: default
                new RouteLoadCalculator.Leg("143", "name:선착장", "01005", 9, 3, 5)); // left out

        List<RouteLoadCalculator.Leg> eight = split(s, legs, new Hours(8, 8));
        assertThat(eight).extracting(RouteLoadCalculator.Leg::passengers).containsExactly(30.0, 1.0);
        assertThat(eight.get(0).boardSeq()).isEqualTo(1);

        double day = 0;
        for (int h = 0; h < 24; h++) {
            day += split(s, legs, new Hours(h, h)).stream().mapToDouble(RouteLoadCalculator.Leg::passengers).sum();
        }
        assertThat(day).isCloseTo(64.0, within(1e-9));                       // 24 hours = the day (without the pier)
        verify(repo, times(1)).profiles(eq("saturday"), anyCollection());   // cached after the first hour
        assertThat(split(s, legs, null)).isSameAs(legs);
    }

    @Test
    void legendMaxIsTheBusiestSingleHourOfTheDay() {
        PeriodOdRepository repo = mock(PeriodOdRepository.class);
        double[] fitted = new double[24];
        fitted[7] = 0.25;
        fitted[8] = 0.75;
        when(repo.profiles(eq("saturday"), anyCollection())).thenReturn(Map.of("143|01001", fitted));
        when(repo.defaultProfile()).thenReturn(new double[24]);
        PeriodOdService s = new PeriodOdService(repo);
        List<RouteLoadCalculator.Leg> legs = List.of(new RouteLoadCalculator.Leg("143", "01001", "01005", 40, 1, 5));

        double max = s.legendMax(legs, "saturday",
                RouteLoadCalculator.Leg::route, RouteLoadCalculator.Leg::boardArs, RouteLoadCalculator.Leg::passengers,
                (l, p) -> new RouteLoadCalculator.Leg(l.route(), l.boardArs(), l.alightArs(), p, l.boardSeq(), l.alightSeq()),
                hour -> hour.stream().mapToDouble(RouteLoadCalculator.Leg::passengers).sum());
        assertThat(max).isEqualTo(30.0);                                     // 8 h: 40 x 0.75
    }

    @Test
    void routeRangeFindsTheNameOrItsOnlySuffixedForm() {
        Map<String, List<java.time.LocalDate>> all = Map.of(
                "1155", List.of(java.time.LocalDate.of(2023, 10, 1), java.time.LocalDate.of(2024, 12, 1)),
                "5522A난곡", List.of(java.time.LocalDate.of(2023, 10, 1), java.time.LocalDate.of(2026, 9, 1)),
                "8773출근", List.of(java.time.LocalDate.of(2024, 1, 1), java.time.LocalDate.of(2026, 9, 1)),
                "8773퇴근", List.of(java.time.LocalDate.of(2024, 1, 1), java.time.LocalDate.of(2026, 9, 1)));

        assertThat(PeriodOdService.routeRangeIn(all, "1155"))
                .containsExactly(java.time.YearMonth.of(2023, 10), java.time.YearMonth.of(2024, 12));
        assertThat(PeriodOdService.routeRangeIn(all, "5522A")).hasSize(2);
        assertThat(PeriodOdService.routeRangeIn(all, "8773")).isEmpty();
        assertThat(PeriodOdService.routeRangeIn(all, "9999")).isEmpty();
    }
}
