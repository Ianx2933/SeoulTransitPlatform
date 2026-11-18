package com.ian.transit.roadload.application;

import com.ian.transit.roadload.infrastructure.RoadLoadQueryRepository.StopPoint;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoadLoadServiceTest {

    @Test
    void parsesRouteLists() {
        assertThat(RoadLoadService.parseRoutes(" 302, 440,302,,333 ")).containsExactly("302", "440", "333");
        assertThatThrownBy(() -> RoadLoadService.parseRoutes(" , ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RoadLoadService.parseRoutes("1,2,3,4,5,6,7,8,9,10,11"))
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
}
