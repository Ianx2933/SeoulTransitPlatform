package com.ian.transit.curatedod.application;

import com.ian.transit.curatedod.infrastructure.StoredRoutePathRepository.StationRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePathServiceTest {

    @Test
    void turnsApiPointsIntoLatLngAndSkipsBadRows() {
        List<double[]> path = RoutePathService.toPath(List.of(
                Map.of("no", "1", "gpsX", "127.178768", "gpsY", "37.436008"),
                Map.of("no", "2", "gpsX", "", "gpsY", "37.4"),
                Map.of("no", "3", "gpsX", "127.1787676642", "gpsY", "37.4359765171")));
        assertThat(path).hasSize(2);
        assertThat(path.get(0)).containsExactly(37.436008, 127.178768);
    }

    private static StationRow st(String variant, int seq, String ars) {
        return new StationRow(variant, "id-" + variant, seq, ars, "s" + ars, 37.5 + seq / 1000.0, 127.0, false);
    }

    @Test
    void picksTheVariantWithTheMostStations() {
        assertThat(RoutePathService.longestVariant(List.of(
                st("한강버스01|서부", 1, "90001"), st("한강버스01|서부", 2, "90002"),
                st("한강버스01|동부옥수", 1, "90003"), st("한강버스01|동부옥수", 2, "90004"),
                st("한강버스01|동부옥수", 3, "90005")))).isEqualTo("한강버스01|동부옥수");
    }

    @Test
    void joinsSegmentsWithoutRepeatingSharedPoints() {
        List<double[]> path = RoutePathService.joinLines(List.of(
                List.of(new double[] {1, 1}, new double[] {1, 2}),
                List.of(new double[] {1, 2}, new double[] {1, 3}),
                List.of()));
        assertThat(path).hasSize(3);
        assertThat(path.get(2)).containsExactly(1, 3);
    }

    @Test
    void stopsOfAllVariantsOncePerArs() {
        var stops = RoutePathService.stopsOf(List.of(
                st("A", 1, "90001"), st("A", 2, "90002"), st("B", 1, "90002"), st("B", 2, "90003"),
                new StationRow("B", "id", 3, null, "no ars", 37.6, 127.1, false)));
        assertThat(stops).extracting("ars").containsExactly("90001", "90002", "90003");
    }
}
