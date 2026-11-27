package com.ian.transit.roadload.application;

import com.ian.transit.roadload.infrastructure.RoadLoadQueryRepository.StopPoint;
import org.junit.jupiter.api.Test;

import com.ian.transit.dongod.application.RouteLoadCalculator;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadLoadServiceTest {

    @Test
    void parsesRouteLists() {
        assertThat(RoadLoadService.parseRoutes(" 302, 440,302,,333 ")).containsExactly("302", "440", "333");
        assertThatThrownBy(() -> RoadLoadService.parseRoutes(" , ")).isInstanceOf(IllegalArgumentException.class);
        String twenty = java.util.stream.IntStream.rangeClosed(1, 20).mapToObj(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        assertThat(RoadLoadService.parseRoutes(twenty)).hasSize(20);
        assertThatThrownBy(() -> RoadLoadService.parseRoutes(twenty + ",21"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requiresFiveDigitArs() {
        assertThat(RoadLoadService.requireArs("1001", "ars")).isEqualTo("01001");
        assertThatThrownBy(() -> RoadLoadService.requireArs("abc", "ars")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoadLoadService.requireArs("123456", "ars")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void pointsAreBusiestFirstAndNeedAPosition() {
        var points = RoadLoadService.points(Map.of("00001", 3.0, "00002", 9.0, "00003", 5.0),
                Map.of("00001", new StopPoint("00001", "A", 37.5, 127.0),
                        "00002", new StopPoint("00002", "B", 37.6, 127.1)));
        assertThat(points).extracting("ars").containsExactly("00002", "00001");
        assertThat(points.get(0).riders()).isEqualTo(9.0);
    }

    @Test
    void oneNumberTwoPlacesKeepsTheAskedPlace() {
        // 10193: 방학동 in the Seoul stop table, 평촌 on route 5530 (stop_ars_route_away).
        var seoul = new RouteLoadCalculator.Leg("1161", "10193", "10200", 5.0, 3, 6);
        var anyang = new RouteLoadCalculator.Leg("5530", "10193", "57045", 7.0, 12, 15);
        Map<String, String> away = Map.of("5530|10193", "41173");

        assertThat(RoadLoadService.samePlace(List.of(seoul, anyang), "10193", false, null, away))
                .containsExactly(seoul);
        assertThat(RoadLoadService.samePlace(List.of(seoul, anyang), "10193", false, "5530", away))
                .containsExactly(anyang);
        assertThat(RoadLoadService.samePlace(List.of(seoul, anyang), "10193", false, null, Map.of()))
                .hasSize(2);
    }
}
