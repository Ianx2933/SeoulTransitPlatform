package com.ian.transit.curatedod.application;

import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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

    @Test
    void reachTakesTheCallersLevelsSortedOnce() {
        // Leaving stop 2: 75 on board, then 70 after 3, 40 after 4, 0 after 5.
        RouteStopDetailResponse stop = analyzer.stop(2, List.of(50.0, 10.0, 50.0, 80.0));
        // 10, 50, 80 in that order: more than 10 stay to 4, more than 50 to 3, never more than 80.
        assertThat(stop.reach()).extracting("untilSeq").containsExactly(4, 3, null);
    }

    @Test
    void rejectsBadReachLevels() {
        assertThatThrownBy(() -> analyzer.stop(2, List.of(0.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.stop(2, List.of(-5.0))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> analyzer.stop(2, List.of(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0, 11.0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void withPassengersKeepsSequencesAndDropsPairsWithoutRiders() {
        List<RouteOdAnalyzer.Leg> typical = List.of(leg(1, 2, 10), leg(1, 4, 30), leg(2, 5, 40));
        List<RouteOdAnalyzer.Leg> hour = RouteOdAnalyzer.withPassengers(typical, Map.of(
                RouteOdAnalyzer.pairKey("ars1", "ars4"), 3.0,
                RouteOdAnalyzer.pairKey("ars2", "ars5"), 0.0));
        assertThat(hour).containsExactly(leg(1, 4, 3.0));
    }

    @Test
    void aStopServedTwiceTakesTheShortestForwardSequence() {
        // T is served at 1 and 6 (a terminus at both ends); S at 3 and 5 (both directions).
        List<RouteOdAnalyzer.Leg> legs = List.of(
                new RouteOdAnalyzer.Leg(1, "T", "T", 2, "B", "B", 10),   // already right
                new RouteOdAnalyzer.Leg(6, "T", "T", 4, "D", "D", 5),    // boarded at the start, put at the end
                new RouteOdAnalyzer.Leg(2, "B", "B", 1, "T", "T", 3),    // alighted at the end, put at the start
                new RouteOdAnalyzer.Leg(3, "S", "S", 6, "T", "T", 4),    // boarded at S on the way back
                new RouteOdAnalyzer.Leg(3, "S", "S", 4, "D", "D", 2),    // S on the way out, right
                new RouteOdAnalyzer.Leg(5, "S", "S", 4, "D", "D", 1));   // wrong pass of S
        List<RouteOdAnalyzer.Leg> placed = RouteOdAnalyzer.placeOnRoute(legs);
        assertThat(placed).extracting(RouteOdAnalyzer.Leg::boardSeq).containsExactly(1, 1, 2, 5, 3, 3);
        assertThat(placed).extracting(RouteOdAnalyzer.Leg::alightSeq).containsExactly(2, 4, 6, 6, 4, 4);
        assertThat(new RouteOdAnalyzer("typical_weekday", "100", 250, legs).stops().unorderedPassengers())
                .isEqualTo(0.0);
    }

    @Test
    void severalStopsTakenTogether() {
        var c = analyzer.stops(List.of(2, 1));
        assertThat(c.seqs()).containsExactly(1, 2);
        assertThat(c.boardings()).isEqualTo(85.0);
        assertThat(c.alightings()).isEqualTo(10.0);
        assertThat(c.inside()).isEqualTo(10.0);
        assertThat(c.destinations()).extracting("seq").containsExactly(5, 4, 2, 3);
        assertThat(c.origins()).extracting("seq").containsExactly(1);
        assertThatThrownBy(() -> analyzer.stops(List.of(9))).isInstanceOf(IllegalArgumentException.class);
    }
}
