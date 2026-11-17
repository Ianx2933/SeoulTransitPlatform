package com.ian.transit.dongod.infrastructure;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteLoadQueryRepositoryTest {

    @Test
    void parsesLineStringToLatLng() {
        List<double[]> p = RouteLoadQueryRepository.parseLineString("LINESTRING(126.97 37.5701234567,126.98 37.571)");
        assertThat(p).hasSize(2);
        assertThat(p.get(0)).containsExactly(37.570123, 126.97);
        assertThat(RouteLoadQueryRepository.parseLineString("POINT(1 2)")).isEmpty();
        assertThat(RouteLoadQueryRepository.parseLineString(null)).isEmpty();
    }
}
