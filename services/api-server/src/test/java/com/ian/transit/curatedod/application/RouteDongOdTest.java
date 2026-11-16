package com.ian.transit.curatedod.application;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RouteDongOdTest {

    private static RouteOdAnalyzer.Leg leg(String from, String to, double p) {
        return new RouteOdAnalyzer.Leg(1, from, "a", 2, to, "b", p);
    }

    @Test
    void padsShortArsNumbersOnly() {
        assertThat(RouteDongOd.padArs("1002")).isEqualTo("01002");
        assertThat(RouteDongOd.padArs(" 48028 ")).isEqualTo("48028");
        assertThat(RouteDongOd.padArs("A1")).isEqualTo("A1");
        assertThat(RouteDongOd.padArs(null)).isNull();
    }

    @Test
    void sumsStopPairsIntoDistrictPairsAndCountsUnplaced() {
        Map<String, String> district = Map.of("01001", "D1", "01002", "D1", "02001", "D2");
        RouteDongOd.Result r = RouteDongOd.aggregate(List.of(
                leg("1001", "2001", 10),
                leg("1002", "2001", 5),
                leg("1001", "1002", 3),
                leg("1001", "9999", 7)), district);

        assertThat(r.total()).isEqualTo(25);
        assertThat(r.unplaced()).isEqualTo(7);
        assertThat(r.flows()).extracting("fromCode", "toCode", "passengers")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("D1", "D2", 15.0),
                        org.assertj.core.groups.Tuple.tuple("D1", "D1", 3.0));
        assertThat(r.byDistrict().get("D1")).containsExactly(18.0, 3.0);
        assertThat(r.byDistrict().get("D2")).containsExactly(0.0, 15.0);
    }
}
