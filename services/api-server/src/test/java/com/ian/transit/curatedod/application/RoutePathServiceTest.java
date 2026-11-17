package com.ian.transit.curatedod.application;

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
}
