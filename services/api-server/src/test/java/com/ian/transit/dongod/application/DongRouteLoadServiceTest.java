package com.ian.transit.dongod.application;

import com.ian.transit.dongod.application.RouteLoadCalculator.SegmentRef;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository.SegmentRow;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DongRouteLoadServiceTest {

    private static List<double[]> path() {
        return List.of(new double[] {37.5, 127.0}, new double[] {37.5, 127.001});
    }

    @Test
    void sumsRoutesSharingTheSameStopPair() {
        var result = new RouteLoadCalculator.Result(Map.of(
                new SegmentRef("302", 5), 10.0,
                new SegmentRef("2312|2312A", 9), 4.0,
                new SegmentRef("440", 2), 1.0), 15, 0, Map.of());
        List<DongRouteLoadResponse.Segment> s = DongRouteLoadService.merge(result, List.of(
                new SegmentRow("302", 5, "01001", "01002", path()),
                new SegmentRow("2312|2312A", 9, "1001", "1002", path()),
                new SegmentRow("440", 2, "01002", "01003", path())));
        assertThat(s).hasSize(2);
        assertThat(s.get(0).fromArs()).isEqualTo("01001");
        assertThat(s.get(0).load()).isEqualTo(14.0);
        assertThat(s.get(0).routes()).containsExactly("2312", "302");
        assertThat(s.get(0).byRoute()).containsEntry("302", 10.0).containsEntry("2312", 4.0);
        assertThat(s.get(1).load()).isEqualTo(1.0);
    }

    @Test
    void segmentWithoutArsStaysOnItsOwnAndMissingGeometryIsSkipped() {
        var result = new RouteLoadCalculator.Result(Map.of(
                new SegmentRef("A", 1), 2.0, new SegmentRef("B", 1), 3.0, new SegmentRef("C", 1), 9.0), 14, 0, Map.of());
        List<DongRouteLoadResponse.Segment> s = DongRouteLoadService.merge(result, List.of(
                new SegmentRow("A", 1, null, "01002", path()),
                new SegmentRow("B", 1, null, "01002", path())));
        assertThat(s).extracting(DongRouteLoadResponse.Segment::load).containsExactly(3.0, 2.0);
    }
}
