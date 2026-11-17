package com.ian.transit.dongod.application;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DongRouteFlowsTest {

    @Test
    void groupsRoutesByRidersWithTopDestinations() {
        DongRouteFlows.Result r = DongRouteFlows.group(List.of(
                new DongRouteFlows.Row("302", "D2", "나동", 10),
                new DongRouteFlows.Row("302", "D3", "다동", 30),
                new DongRouteFlows.Row("302", "D2", "나동", 5),
                new DongRouteFlows.Row("440", "D2", "나동", 8),
                new DongRouteFlows.Row("440", null, null, 4)), 1);

        assertThat(r.boardings()).isEqualTo(57);
        assertThat(r.unplaced()).isEqualTo(4);
        assertThat(r.routes()).extracting("route").containsExactly("302", "440");
        assertThat(r.routes().get(0).boardings()).isEqualTo(45.0);
        assertThat(r.routes().get(0).destinations()).extracting("code", "passengers")
                .containsExactly(org.assertj.core.groups.Tuple.tuple("D3", 30.0));
        assertThat(r.routes().get(1).boardings()).isEqualTo(12.0);
        assertThat(r.routes().get(1).destinations()).extracting("code").containsExactly("D2");
    }
}
