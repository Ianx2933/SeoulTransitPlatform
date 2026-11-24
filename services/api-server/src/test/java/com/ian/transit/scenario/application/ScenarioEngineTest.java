package com.ian.transit.scenario.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.ian.transit.scenario.application.ScenarioEngine.Kind;
import com.ian.transit.scenario.application.ScenarioEngine.Leg;
import com.ian.transit.scenario.application.ScenarioEngine.Operation;
import com.ian.transit.scenario.infrastructure.ScenarioQueryRepository.StationAt;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Route A serves stops 00001..00006 at sequences 1..6:
 *   1 -> 3: 10 riders, 2 -> 5: 20 riders, 4 -> 6: 30 riders (60 a day).
 * On board: 1-2 10, 2-3 30, 3-4 20, 4-5 50, 5-6 30.
 */
class ScenarioEngineTest {

    private static final List<Leg> A = List.of(
            new Leg("A", "00001", "00003", 1, 3, 10),
            new Leg("A", "00002", "00005", 2, 5, 20),
            new Leg("A", "00004", "00006", 4, 6, 30));

    private static final ScenarioEngine.PairService ONLY_A = (b, a) -> Set.of("A");

    private static Operation op(Kind kind, String route, Integer from, Integer to, Integer at, Double share,
                                String name, List<String> stops) {
        return new Operation(kind, route, from, to, at, share, name, stops, null);
    }

    private static double total(Map<String, List<Leg>> byRoute) {
        return byRoute.values().stream().mapToDouble(ScenarioEngine::total).sum();
    }

    @Test
    void loadsAreRidersOnBoardBetweenStops() {
        assertThat(ScenarioEngine.loads(A)).isEqualTo(
                Map.of(1, 10.0, 2, 30.0, 3, 20.0, 4, 50.0, 5, 30.0));
    }

    @Test
    void aShortTurnTakesItsShareOfTheTripsInsideTheSection() {
        var r = ScenarioEngine.apply(A, List.of(op(Kind.SHORT_TURN, "A", 2, 5, null, 0.5, null, null)), ONLY_A);
        assertThat(r.moved()).isEqualTo(10.0);
        assertThat(ScenarioEngine.total(r.after().get("A"))).isEqualTo(50.0);
        assertThat(ScenarioEngine.total(r.after().get("A 단축"))).isEqualTo(10.0);
        assertThat(total(r.after())).isEqualTo(60.0);
        assertThat(ScenarioEngine.loads(r.after().get("A")).get(4)).isEqualTo(40.0);
        // The short-turn riders still ride route A's road.
        assertThat(r.afterPlaced()).allMatch(p -> "A".equals(p.roadRoute()));
    }

    @Test
    void aSplitCountsTheRidersCrossingTheSplitAsNewTransfers() {
        var r = ScenarioEngine.apply(A, List.of(op(Kind.SPLIT, "A", null, null, 4, null, null, null)), ONLY_A);
        // 2 -> 5 crosses 4; 4 -> 6 boards at 4 and 1 -> 3 ends before it.
        assertThat(r.newTransfers()).isEqualTo(20.0);
        assertThat(ScenarioEngine.loads(r.after().get("A"))).isEqualTo(ScenarioEngine.loads(A));
        assertThat(r.changes().get("A").splitAt()).isEqualTo(4);
    }

    @Test
    void aCutLosesTripsWithAnEndOutsideAndCountsThoseAnotherRouteLinks() {
        var keep = op(Kind.CUT, "A", 1, 5, null, null, null, null);
        var alone = ScenarioEngine.apply(A, List.of(keep), ONLY_A);
        assertThat(alone.lost()).isEqualTo(30.0);
        assertThat(alone.lostWithAlternative()).isEqualTo(0.0);

        var withB = ScenarioEngine.apply(A, List.of(keep), (b, a) -> Set.of("A", "B"));
        assertThat(withB.lostWithAlternative()).isEqualTo(30.0);
        // Riders are conserved: before = after + lost.
        assertThat(total(withB.before())).isEqualTo(total(withB.after()) + withB.lost());
    }

    @Test
    void aNewRouteTakesOneOverKPlusOneOfThePairsItServesInOrder() {
        var r = ScenarioEngine.apply(A,
                List.of(op(Kind.NEW_ROUTE, null, null, null, null, null, "N", List.of("00002", "00005", "00009"))),
                ONLY_A);
        // Only 2 -> 5 has both stops on N in N's order; one route links it, so N takes half.
        assertThat(r.moved()).isEqualTo(10.0);
        Leg n = r.after().get("N").get(0);
        assertThat(n.boardSeq()).isEqualTo(1);
        assertThat(n.alightSeq()).isEqualTo(2);
        assertThat(n.passengers()).isEqualTo(10.0);
        assertThat(total(r.after())).isEqualTo(60.0);
        assertThat(r.changes().get("A").after()).isEqualTo(50.0);
        assertThat(r.afterPlaced()).anyMatch(p -> p.roadRoute() == null && p.drawnIndexFrom() == 0
                && p.drawnIndexTo() == 1);
    }

    @Test
    void aGivenShareOverridesTheRuleAndTheWrongDirectionIsNotServed() {
        var shared = ScenarioEngine.apply(A,
                List.of(op(Kind.NEW_ROUTE, null, null, null, null, 0.3, "N", List.of("00002", "00005"))), ONLY_A);
        assertThat(shared.moved()).isCloseTo(6.0, within(1e-9));

        var reversed = ScenarioEngine.apply(A,
                List.of(op(Kind.NEW_ROUTE, null, null, null, null, null, "N", List.of("00005", "00002"))), ONLY_A);
        assertThat(reversed.moved()).isEqualTo(0.0);
    }

    @Test
    void redrawingARouteAsANewOneSplitsItsRidersInHalf() {
        var r = ScenarioEngine.apply(A, List.of(op(Kind.NEW_ROUTE, null, null, null, null, null, "A2",
                List.of("00001", "00002", "00003", "00004", "00005", "00006"))), ONLY_A);
        assertThat(r.moved()).isEqualTo(30.0);
        assertThat(ScenarioEngine.total(r.after().get("A2"))).isEqualTo(30.0);
        assertThat(ScenarioEngine.loads(r.after().get("A2")).get(4)).isEqualTo(25.0);
    }

    @Test
    void anUnknownRouteIsRefused() {
        assertThatThrownBy(() -> ScenarioEngine.apply(A,
                List.of(op(Kind.SPLIT, "Z", null, null, 3, null, null, null)), ONLY_A))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedRoutesLinkTwoStopsOnlyInTheirRidingOrder() {
        var pairs = ScenarioService.linking(List.of(
                new StationAt("R", "R", 1, "00001"),
                new StationAt("R", "R", 5, "5"),
                new StationAt("S", "S|S2", 3, "00005"),
                new StationAt("S", "S|S2", 9, "00001")));
        assertThat(pairs.routesLinking("00001", "00005")).containsExactly("R");
        assertThat(pairs.routesLinking("00005", "00001")).containsExactly("S");
        assertThat(pairs.routesLinking("00001", "00009")).isEmpty();
    }
}
