package com.ian.transit.curatedod.application;

import com.ian.transit.map.month.BusMonthDemandRepository.StopPoint;
import com.ian.transit.scenario.infrastructure.ScenarioQueryRepository.PairSegment;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StopPathServiceTest {

    private static final StopPoint A = new StopPoint("01001", "가", 37.5000, 127.0000);
    private static final StopPoint B = new StopPoint("01002", "나", 37.5030, 127.0000);
    private static final StopPoint C = new StopPoint("01003", "다", 37.5060, 127.0000);

    @Test
    void padsAndKeepsOrder() {
        assertThat(StopPathService.parse("1001, 01002,123")).containsExactly("01001", "01002", "00123");
    }

    @Test
    void rejectsBadLists() {
        assertThatThrownBy(() -> StopPathService.parse("01001")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StopPathService.parse("01001,abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StopPathService.parse(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usesStoredSegmentsAndFallsBackToStraight() {
        var stored = new PairSegment("01001", "01002", 350,
                List.of(new double[] {37.5, 127.0}, new double[] {37.5015, 127.0005}, new double[] {37.503, 127.0}));
        var path = StopPathService.assemble(List.of("01001", "01002", "01003"),
                Map.of("01001", A, "01002", B, "01003", C), Map.of("01001>01002", stored));

        assertThat(path.stops()).hasSize(3);
        assertThat(path.segments()).hasSize(2);
        assertThat(path.segments().get(0).straight()).isFalse();
        assertThat(path.segments().get(0).path()).hasSize(3);
        assertThat(path.segments().get(1).straight()).isTrue();
        assertThat(path.segments().get(1).lengthM()).isBetween(320.0, 345.0);
    }

    @Test
    void skipsUnknownStopsAndLongDetours() {
        var detour = new PairSegment("01001", "01003", 5_000, List.of(new double[] {37.5, 127.0}, new double[] {37.506, 127.0}));
        var path = StopPathService.assemble(List.of("01001", "09999", "01003"),
                Map.of("01001", A, "01003", C), Map.of("01001>01003", detour));

        assertThat(path.missingStops()).isEqualTo(1);
        assertThat(path.segments()).hasSize(1);
        assertThat(path.segments().get(0).fromArs()).isEqualTo("01001");
        assertThat(path.segments().get(0).toArs()).isEqualTo("01003");
        assertThat(path.segments().get(0).straight()).isTrue();
    }
}
