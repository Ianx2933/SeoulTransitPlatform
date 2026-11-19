package com.ian.transit.dongod.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BusOutsideTest {

    @Test
    void ridersGoIntoBandsAndCitiesAndUnplacedStopsStayApart() {
        BusOutside.Result r = BusOutside.summarize(List.of(
                new BusOutside.Row("31191510", "풍덕천1동", 18.0, 30),   // 용인시 수지구
                new BusOutside.Row("31192520", "구갈동", 25.0, 10),     // 용인시 기흥구: same city
                new BusOutside.Row("31110510", "중앙동", 4.0, 50),      // 과천시
                new BusOutside.Row("99990000", "어딘가", 60.0, 5),      // a key the table does not list
                new BusOutside.Row(null, null, null, 7)));              // other stop not placed
        assertThat(r.total()).isEqualTo(95.0);
        assertThat(r.unlocated()).isEqualTo(7.0);
        assertThat(r.bands()).extracting(DongReach.Band::key)
                .containsExactly("0-1", "1-3", "3-5", "5-10", "10-20", "20+");
        assertThat(r.bands()).extracting(DongReach.Band::total)
                .containsExactly(0.0, 0.0, 50.0, 0.0, 30.0, 15.0);
        assertThat(r.medianKm()).isEqualTo(4.0);                         // 50 of 95 riders at 4 km
        assertThat(r.areas()).extracting(BusOutside.Area::code).containsExactly("3111", "3119", "9999");
        assertThat(r.areas()).extracting(BusOutside.Area::passengers).containsExactly(50.0, 40.0, 5.0);
        assertThat(r.areas().get(1).name()).isEqualTo("경기 용인시");
        assertThat(r.areas().get(1).nameEn()).isEqualTo("Yongin");
        assertThat(r.areas().get(2).name()).isNull();
    }

    @Test
    void keepsTheTopTenCities() {
        List<BusOutside.Row> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(new BusOutside.Row(String.format("31%02d0510", i), "동", 10.0, i));
        }
        BusOutside.Result r = BusOutside.summarize(rows);
        assertThat(r.areas()).hasSize(BusOutside.TOP_AREAS);
        assertThat(r.areas().get(0).code()).isEqualTo("3112");
        assertThat(r.total()).isEqualTo(78.0);                            // every row counts, not just the top ten
    }

    @Test
    void nothingOutsideMeansNoMedian() {
        BusOutside.Result r = BusOutside.summarize(List.of(new BusOutside.Row(null, null, null, 3)));
        assertThat(r.total()).isZero();
        assertThat(r.unlocated()).isEqualTo(3.0);
        assertThat(r.medianKm()).isNull();
        assertThat(r.areas()).isEmpty();
    }
}
