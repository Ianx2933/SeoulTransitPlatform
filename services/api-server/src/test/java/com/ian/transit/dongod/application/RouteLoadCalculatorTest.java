package com.ian.transit.dongod.application;

import com.ian.transit.dongod.application.RouteLoadCalculator.Leg;
import com.ian.transit.dongod.application.RouteLoadCalculator.SegmentRef;
import com.ian.transit.dongod.application.RouteLoadCalculator.Station;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RouteLoadCalculatorTest {

    /** Stations "variant" seq 1..n with the given ARS numbers. */
    private static List<Station> line(String route, String variant, String... ars) {
        List<Station> out = new ArrayList<>();
        for (int i = 0; i < ars.length; i++) {
            out.add(new Station(route, variant, i + 1, ars[i]));
        }
        return out;
    }

    @Test
    void legLoadsEverySegmentFromBoardingToBeforeAlighting() {
        var r = RouteLoadCalculator.compute(
                List.of(new Leg("302", "1001", "01003", 10), new Leg("302", "01002", "01004", 5)),
                line("302", "302", "01001", "01002", "01003", "01004"));
        assertThat(r.loads()).containsEntry(new SegmentRef("302", 1), 10.0)
                .containsEntry(new SegmentRef("302", 2), 15.0)
                .containsEntry(new SegmentRef("302", 3), 5.0)
                .hasSize(3);
        assertThat(r.placed()).isEqualTo(15.0);
        assertThat(r.unplaced()).isZero();
    }

    @Test
    void loopRouteUsesTheShortestForwardSpan() {
        // stop 01002 is served on the way out (seq 2) and back (seq 4)
        var r = RouteLoadCalculator.compute(List.of(new Leg("R", "01002", "01005", 1)),
                line("R", "R", "01001", "01002", "01003", "01002", "01005"));
        assertThat(r.loads()).containsOnlyKeys(new SegmentRef("R", 4));
    }

    @Test
    void topisSequencesPickTheOccurrenceOfAStopServedTwice() {
        // 6411-like: 01002 at seq 2 and 4. Boarding at seq 2 and alighting at
        // 01005 rides the whole loop; without sequences the short span wins.
        List<Station> loop = line("R", "R", "01001", "01002", "01003", "01002", "01005");
        var withSeq = RouteLoadCalculator.compute(List.of(new Leg("R", "01002", "01005", 1, 2, 5)), loop);
        assertThat(withSeq.loads()).containsOnlyKeys(new SegmentRef("R", 2), new SegmentRef("R", 3), new SegmentRef("R", 4));
        var back = RouteLoadCalculator.compute(List.of(new Leg("R", "01002", "01005", 1, 4, 5)), loop);
        assertThat(back.loads()).containsOnlyKeys(new SegmentRef("R", 4));
        // sequences on a stop served once change nothing
        var once = RouteLoadCalculator.compute(List.of(new Leg("R", "01001", "01003", 1, 9, 9)), loop);
        assertThat(once.loads()).containsOnlyKeys(new SegmentRef("R", 1), new SegmentRef("R", 2));
    }

    @Test
    void ridersFromTheVirtualDepotStopRideFromTheFirstStation() {
        List<Station> l = line("143", "143", "01001", "01002", "01003");
        var r = RouteLoadCalculator.compute(List.of(new Leg("143", "00000", "01003", 6, 0, 3)), l);
        assertThat(r.loads()).containsEntry(new SegmentRef("143", 1), 6.0).containsEntry(new SegmentRef("143", 2), 6.0).hasSize(2);
        assertThat(r.unplaced()).isZero();
        // without the depot sequence an all-zero ARS stays unplaced
        assertThat(RouteLoadCalculator.compute(List.of(new Leg("143", "00000", "01003", 6)), l).unplaced()).isEqualTo(6.0);
    }

    @Test
    void variantsAreChosenByWhichOneServesBothStopsInOrder() {
        List<Station> stations = new ArrayList<>(line("2312", "2312|2312A", "00001", "00002", "00003"));
        stations.addAll(line("2312", "2312|2312B", "00003", "00002", "00001"));
        var r = RouteLoadCalculator.compute(List.of(
                new Leg("2312", "1", "3", 4), new Leg("2312", "3", "2", 2)), stations);
        assertThat(r.loads()).containsEntry(new SegmentRef("2312|2312A", 1), 4.0)
                .containsEntry(new SegmentRef("2312|2312A", 2), 4.0)
                .containsEntry(new SegmentRef("2312|2312B", 1), 2.0)
                .hasSize(3);
    }

    @Test
    void legsWithoutAPathOrInReverseAreUnplacedByRoute() {
        var r = RouteLoadCalculator.compute(List.of(
                new Leg("노원13", "1", "2", 7),
                new Leg("302", "00002", "00001", 3),
                new Leg("302", "00001", "00002", 0)), line("302", "302", "00001", "00002"));
        assertThat(r.loads()).isEmpty();
        assertThat(r.unplaced()).isEqualTo(10.0);
        assertThat(r.unplacedByRoute()).containsEntry("노원13", 7.0).containsEntry("302", 3.0);
    }

    @Test
    void padsArsNumbers() {
        assertThat(RouteLoadCalculator.pad("1001")).isEqualTo("01001");
        assertThat(RouteLoadCalculator.pad("01001")).isEqualTo("01001");
        assertThat(RouteLoadCalculator.pad("0")).isNull();
        assertThat(RouteLoadCalculator.pad(" ")).isNull();
        assertThat(RouteLoadCalculator.pad(null)).isNull();
    }

    @Test
    void selectLinkKeepsOnlyLegsCrossingTheSegment() {
        List<Station> stations = new ArrayList<>(line("302", "302", "00001", "00002", "00003", "00004"));
        stations.addAll(line("440", "440", "00009", "00002", "00003"));
        var r = RouteLoadCalculator.compute(List.of(
                new Leg("302", "1", "4", 5),      // crosses 2 -> 3
                new Leg("302", "3", "4", 7),      // after it
                new Leg("440", "9", "3", 2),      // crosses on another route
                new Leg("999", "1", "2", 4)),     // no path: left out, not unplaced
                stations, (a, b) -> a.equals("00002") && b.equals("00003"));
        assertThat(r.placed()).isEqualTo(7.0);
        assertThat(r.unplaced()).isZero();
        assertThat(r.placedLegs()).extracting("route", "boardArs", "alightArs")
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("302", "00001", "00004"),
                        org.assertj.core.groups.Tuple.tuple("440", "00009", "00003"));
        assertThat(r.loads()).containsEntry(new SegmentRef("302", 3), 5.0).doesNotContainKey(new SegmentRef("302", 4));
    }
}
