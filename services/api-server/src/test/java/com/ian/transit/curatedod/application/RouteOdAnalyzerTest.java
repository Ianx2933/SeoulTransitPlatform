package com.ian.transit.curatedod.application;

import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Route analysis on a five-stop route with hand-checkable numbers:
 *
 *   1 -> 2: 10   1 -> 4: 30   2 -> 3: 5   2 -> 5: 40   3 -> 5: 20   4 -> 2: 7 (unordered)
 */
class RouteOdAnalyzerTest {

    private static RouteOdAnalyzer.Leg leg(int b, int a, double p) {
        return new RouteOdAnalyzer.Leg(b, "ars" + b, "stop" + b, a, "ars" + a, "stop" + a, p);
    }

    private final RouteOdAnalyzer analyzer = new RouteOdAnalyzer("typical_weekday", "R1", 250, List.of(
            leg(1, 2, 10), leg(1, 4, 30), leg(2, 3, 5), leg(2, 5, 40), leg(3, 5, 20), leg(4, 2, 7)));

    @Test
    void loadAfterEachStopIsBoardingsMinusAlightingsSoFar() {
        RouteStopsResponse stops = analyzer.stops();
        assertThat(stops.totalPassengers()).isEqualTo(112.0);
        assertThat(stops.unorderedPassengers()).isEqualTo(7.0);
        assertThat(stops.stops()).extracting("onBoardAfter").containsExactly(40.0, 75.0, 90.0, 60.0, 0.0);
        assertThat(stops.stops().get(1).boardings()).isEqualTo(45.0);
        assertThat(stops.stops().get(1).alightings()).isEqualTo(10.0);
    }

    @Test
    void segmentSplitsTripsByWhereTheyStartAndEnd() {
        RouteSegmentResponse segment = analyzer.segment(2, 3);
        assertThat(segment.within()).isEqualTo(5.0);
        assertThat(segment.leaving()).isEqualTo(60.0);    // 2->5 and 3->5
        assertThat(segment.entering()).isEqualTo(10.0);   // 1->2
        assertThat(segment.through()).isEqualTo(30.0);    // 1->4
        assertThat(segment.stops()).extracting("leavingOnBoard").containsExactly(40.0, 60.0);
        assertThat(segment.leavingTo()).extracting("seq").containsExactly(5);
    }

    @Test
    void reachIsTheLastStopAfterWhichMoreThanTheLevelAreStillOnBoard() {
        // Leaving stop 2: 30 to stop 4, 5 to stop 3, 40 to stop 5 (75 on board).
        RouteStopDetailResponse stop = analyzer.stop(2);
        assertThat(stop.onBoardAfter()).isEqualTo(75.0);
        assertThat(stop.destinations()).extracting("seq").containsExactly(5, 3);
        assertThat(stop.origins()).extracting("seq").containsExactly(1);
        // after 3: 70 on board, after 4: 40, after 5: 0
        assertThat(stop.reach()).extracting("untilSeq").containsExactly(4, 3, null, null);
    }

    @Test
    void rejectsUnknownStopsAndReversedSegments() {
        assertThatThrownBy(() -> analyzer.stop(9)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.segment(3, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}
