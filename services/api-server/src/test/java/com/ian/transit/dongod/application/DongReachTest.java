package com.ian.transit.dongod.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DongReachTest {

    private static DongReach.Row row(Double km, double subway, double bus) {
        return new DongReach.Row(km, false, subway, bus, subway + bus);
    }

    @Test
    void ridersFallIntoBandsAndTheMedianIsWeightedByRiders() {
        DongReach.Result r = DongReach.summarize(List.of(
                row(0.0, 0, 10),          // inside the district
                row(0.8, 0, 20),
                row(2.5, 5, 5),
                row(12.0, 50, 10),
                new DongReach.Row(null, true, 3, 2, 5),     // outside Seoul
                new DongReach.Row(null, false, 0, 1, 1)));  // no boundary
        assertThat(r.total()).isEqualTo(106.0);
        assertThat(r.outside()).isEqualTo(5.0);
        assertThat(r.unknown()).isEqualTo(1.0);
        assertThat(r.bands()).extracting(DongReach.Band::key)
                .containsExactly("same", "0-1", "1-3", "3-5", "5-10", "10-20", "20+");
        assertThat(r.bands()).extracting(DongReach.Band::total)
                .containsExactly(10.0, 20.0, 10.0, 0.0, 0.0, 60.0, 0.0);
        // 100 riders with a distance: the 50th sits in the 12 km row
        assertThat(r.medianKm()).isEqualTo(12.0);
        assertThat(r.busMedianKm()).isEqualTo(0.8);       // 45 bus riders: 10 + 20 reach 22.5
        assertThat(r.subwayMedianKm()).isEqualTo(12.0);
        assertThat(r.meanKm()).isEqualTo(7.6);            // (0 + 16 + 25 + 720) / 100
    }

    @Test
    void noDistanceMeansNoMedian() {
        DongReach.Result r = DongReach.summarize(List.of(new DongReach.Row(null, true, 1, 1, 2)));
        assertThat(r.medianKm()).isNull();
        assertThat(r.meanKm()).isNull();
        assertThat(r.outside()).isEqualTo(2.0);
    }
}
