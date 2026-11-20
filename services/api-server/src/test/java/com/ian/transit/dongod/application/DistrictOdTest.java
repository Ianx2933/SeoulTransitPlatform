package com.ian.transit.dongod.application;

import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import com.ian.transit.period.infrastructure.PeriodDongRepository.Pair;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DistrictOdTest {

    // 사직동 11010530 and 청운효자동 11010540 (종로구), 회현동 11020550 (중구), outside
    private static final List<Pair> PAIRS = List.of(
            new Pair("11010530", "사직동", "11010530", "사직동", 1, 2),
            new Pair("11010530", "사직동", "11010540", "청운효자동", 3, 4),
            new Pair("11010530", "사직동", "11020550", "회현동", 5, 0),
            new Pair("11010530", "사직동", "outside", "outside", 6, 1),
            new Pair("11020550", "회현동", "11010530", "사직동", 2, 2),
            new Pair("outside", "outside", "11010540", "청운효자동", 0, 9));

    @Test
    void areaAndUnitFollowTheSqlFilters() {
        assertThat(DistrictOd.inArea(DongArea.GU, "11010", "11010540")).isTrue();
        assertThat(DistrictOd.inArea(DongArea.GU, "11010", "outside")).isFalse();
        assertThat(DistrictOd.inArea(DongArea.CITY, "11", "outside")).isFalse();
        assertThat(DistrictOd.inArea(DongArea.GROUP, "11010530,11020550", "11020550")).isTrue();
        assertThat(DistrictOd.unit(DongArea.GU, "11010540")).isEqualTo("11010");
        assertThat(DistrictOd.unit(DongArea.CITY, "11010540")).isEqualTo("11");
        assertThat(DistrictOd.unit(DongArea.GU, "outside")).isEqualTo("outside");
    }

    @Test
    void totalsAndFlowsOfADong() {
        var t = DistrictOd.totals(PAIRS, DongArea.DONG, "11010530");
        assertThat(t.departures()).isEqualTo(3 + 7 + 5 + 7);
        assertThat(t.subwayDepartures()).isEqualTo(15);
        assertThat(t.busDepartures()).isEqualTo(7);
        assertThat(t.arrivals()).isEqualTo(3 + 4);
        assertThat(t.dongName()).isEqualTo("사직동");

        List<DongOdResponse.Flow> byGu = DistrictOd.flows(PAIRS, DongArea.DONG, "11010530", DongArea.GU, true, 15);
        assertThat(byGu).extracting(DongOdResponse.Flow::code).containsExactly("11010", "outside", "11020");
        assertThat(byGu.get(0).total()).isEqualTo(10.0);                  // inside the gu: 3 + 7
        assertThat(byGu.get(0).name()).isEqualTo("종로구");
        assertThat(DistrictOd.flows(PAIRS, DongArea.DONG, "11010530", DongArea.DONG, true, 2)).hasSize(2);
    }

    @Test
    void summaryByGuLeavesOutsideOut() {
        List<DongOdSummaryRow> gu = DistrictOd.summary(PAIRS, DongArea.GU);
        Map<String, DongOdSummaryRow> by = new java.util.HashMap<>();
        gu.forEach(r -> by.put(r.code(), r));
        assertThat(by).containsOnlyKeys("11010", "11020");
        assertThat(by.get("11010").departures()).isEqualTo(22.0);
        assertThat(by.get("11010").arrivals()).isEqualTo(3 + 7 + 4 + 9);
        assertThat(by.get("11020").busArrivals()).isEqualTo(0.0);
    }

    @Test
    void reachRowsMeasureBetweenDistrictPoints() {
        Map<String, double[]> points = Map.of(
                "11010530", new double[] {37.005, 127.005},
                "11010540", new double[] {37.005, 127.025});
        List<DongReach.Row> rows = DistrictOd.reachRows(PAIRS, DongArea.DONG, "11010530", true, points);
        assertThat(rows).hasSize(4);
        assertThat(rows.get(0).km()).isEqualTo(0.0);                         // inside the dong
        assertThat(rows.get(1).km()).isCloseTo(1.776, within(0.01));          // 0.02 degrees east at 37 N
        assertThat(rows.get(2).km()).isNull();                                // no point for 회현동
        assertThat(rows.get(3).outside()).isTrue();
    }
}
