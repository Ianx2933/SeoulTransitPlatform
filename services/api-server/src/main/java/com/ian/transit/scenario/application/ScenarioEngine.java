package com.ian.transit.scenario.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Applies a route scenario to the representative-day OD legs and reports what
 * changes. Pure: no database, so every rule is unit tested directly.
 *
 * A leg is one route's riders from one stop to another (TOPIS stop sequences
 * on existing routes, the drawn stop order on a new route). The engine never
 * creates or loses riders except where an operation says so (a cut route
 * section); riders moved to a new route or a short-turn service keep their
 * stops. Behaviour (new demand, riders coming from the subway, route choice)
 * is not modelled: the share of riders who move is an assumption the request
 * gives, or the default rule below.
 *
 * Operations, applied in order:
 *   SHORT_TURN  route, fromSeq..toSeq, share s (default 0.5): of riders whose
 *               trip lies inside the section, s ride the short-turn service.
 *   SPLIT       route, atSeq: riders crossing atSeq must change buses there.
 *               Loads are unchanged; the crossing riders are new transfers.
 *   CUT         route, keepFrom..keepTo: riders with an end outside lose the
 *               route; those whose two stops another stored route also links
 *               in order are counted as able to move to it.
 *   NEW_ROUTE   name, stops (ARS in order), share (default 1/(k+1), k = stored
 *               routes linking the pair): riders of any route whose boarding
 *               and alighting stops are both on the new route, in its order,
 *               move by that share. An extension is drawn as a new route made
 *               of the route's stops plus the new ones.
 */
public final class ScenarioEngine {

    private ScenarioEngine() {
    }

    public enum Kind { SHORT_TURN, SPLIT, CUT, NEW_ROUTE }

    /** One operation; fields not used by its kind are null. */
    public record Operation(Kind kind, String route, Integer fromSeq, Integer toSeq, Integer atSeq,
                            Double share, String name, List<String> stops, Double headwayMin) {
    }

    /** A leg: route, padded ARS of both ends, their sequences on the route, riders. */
    public record Leg(String route, String boardArs, String alightArs, int boardSeq, int alightSeq,
                      double passengers) {
    }

    /** The stored routes that link two stops in this order. */
    @FunctionalInterface
    public interface PairService {
        java.util.Set<String> routesLinking(String boardArs, String alightArs);
    }

    /** Which existing route a leg's riders physically ride on (for the road map), or null for a drawn route. */
    public record Placed(Leg leg, String roadRoute, Integer drawnIndexFrom, Integer drawnIndexTo) {
    }

    public record RouteChange(String route, String kind, String baseRoute, double before, double after,
                              Integer splitAt, Integer fromSeq, Integer toSeq) {
    }

    public record Result(Map<String, List<Leg>> before, Map<String, List<Leg>> after,
                         List<Placed> afterPlaced, Map<String, RouteChange> changes,
                         Map<String, List<String>> drawnStops,
                         double moved, double newTransfers, double lost, double lostWithAlternative) {
    }

    public static Result apply(List<Leg> legs, List<Operation> operations, PairService pairs) {
        Map<String, List<Leg>> before = group(legs);
        // Mutable copy: route -> legs; the stored route each one rides on kept alongside.
        Map<String, List<Leg>> state = new LinkedHashMap<>();
        Map<String, String> roadRouteOf = new HashMap<>();
        before.forEach((route, list) -> {
            state.put(route, new ArrayList<>(list));
            roadRouteOf.put(route, route);
        });
        Map<String, RouteChange> changes = new LinkedHashMap<>();
        Map<String, List<String>> drawn = new LinkedHashMap<>();
        double moved = 0;
        double transfers = 0;
        double lost = 0;
        double lostAlt = 0;

        for (Operation op : operations) {
            switch (op.kind()) {
                case SHORT_TURN -> {
                    List<Leg> main = requireRoute(state, op.route());
                    double share = op.share() != null ? op.share() : 0.5;
                    String name = op.name() != null && !op.name().isBlank() ? op.name().trim()
                            : op.route() + " 단축";
                    List<Leg> kept = new ArrayList<>();
                    List<Leg> shortLegs = state.computeIfAbsent(name, k -> new ArrayList<>());
                    for (Leg l : main) {
                        if (l.boardSeq() >= op.fromSeq() && l.alightSeq() <= op.toSeq()) {
                            double m = l.passengers() * share;
                            shortLegs.add(with(l, name, m));
                            kept.add(with(l, l.route(), l.passengers() - m));
                            moved += m;
                        } else {
                            kept.add(l);
                        }
                    }
                    state.put(op.route(), kept);
                    roadRouteOf.put(name, roadRouteOf.get(op.route()));
                    changes.put(name, new RouteChange(name, "short_turn", op.route(), 0, 0, null,
                            op.fromSeq(), op.toSeq()));
                    changes.putIfAbsent(op.route(), new RouteChange(op.route(), "existing", null, 0, 0, null, null, null));
                }
                case SPLIT -> {
                    List<Leg> main = requireRoute(state, op.route());
                    for (Leg l : main) {
                        if (l.boardSeq() < op.atSeq() && l.alightSeq() > op.atSeq()) {
                            transfers += l.passengers();
                        }
                    }
                    changes.put(op.route(), new RouteChange(op.route(), "split", null, 0, 0, op.atSeq(), null, null));
                }
                case CUT -> {
                    List<Leg> main = requireRoute(state, op.route());
                    List<Leg> kept = new ArrayList<>();
                    for (Leg l : main) {
                        if (l.boardSeq() >= op.fromSeq() && l.alightSeq() <= op.toSeq()) {
                            kept.add(l);
                        } else {
                            lost += l.passengers();
                            java.util.Set<String> others = new java.util.HashSet<>(
                                    pairs.routesLinking(l.boardArs(), l.alightArs()));
                            others.remove(op.route());
                            if (!others.isEmpty()) {
                                lostAlt += l.passengers();
                            }
                        }
                    }
                    state.put(op.route(), kept);
                    changes.put(op.route(), new RouteChange(op.route(), "cut", null, 0, 0, null,
                            op.fromSeq(), op.toSeq()));
                }
                case NEW_ROUTE -> {
                    String name = op.name() != null && !op.name().isBlank() ? op.name().trim() : "새 노선";
                    if (state.containsKey(name)) {
                        throw new IllegalArgumentException("route name already used: " + name);
                    }
                    List<String> stops = op.stops();
                    Map<String, Integer> index = new HashMap<>();
                    for (int i = 0; i < stops.size(); i++) {
                        index.putIfAbsent(stops.get(i), i);
                    }
                    List<Leg> newLegs = new ArrayList<>();
                    for (Map.Entry<String, List<Leg>> e : state.entrySet()) {
                        List<Leg> next = new ArrayList<>();
                        for (Leg l : e.getValue()) {
                            Integer ib = index.get(l.boardArs());
                            Integer ia = index.get(l.alightArs());
                            if (ib == null || ia == null || ib >= ia || l.passengers() <= 0) {
                                next.add(l);
                                continue;
                            }
                            double share = op.share() != null ? op.share()
                                    : 1.0 / (Math.max(1, pairs.routesLinking(l.boardArs(), l.alightArs()).size()) + 1);
                            double m = l.passengers() * share;
                            next.add(with(l, l.route(), l.passengers() - m));
                            newLegs.add(new Leg(name, l.boardArs(), l.alightArs(), ib + 1, ia + 1, m));
                            moved += m;
                            changes.putIfAbsent(e.getKey(), new RouteChange(e.getKey(), "existing", null, 0, 0,
                                    null, null, null));
                        }
                        e.setValue(next);
                    }
                    state.put(name, newLegs);
                    roadRouteOf.put(name, null);
                    drawn.put(name, List.copyOf(stops));
                    changes.put(name, new RouteChange(name, "new", null, 0, 0, null, null, null));
                }
            }
        }

        Map<String, List<Leg>> after = new LinkedHashMap<>();
        List<Placed> placed = new ArrayList<>();
        state.forEach((route, list) -> {
            List<Leg> nonZero = list.stream().filter(l -> l.passengers() > 1e-9).toList();
            after.put(route, nonZero);
            String road = roadRouteOf.get(route);
            for (Leg l : nonZero) {
                placed.add(road != null
                        ? new Placed(l, road, null, null)
                        : new Placed(l, null, l.boardSeq() - 1, l.alightSeq() - 1));
            }
        });
        Map<String, RouteChange> withTotals = new LinkedHashMap<>();
        changes.forEach((route, c) -> withTotals.put(route, new RouteChange(c.route(), c.kind(), c.baseRoute(),
                total(before.get(route)), total(after.get(route)), c.splitAt(), c.fromSeq(), c.toSeq())));
        return new Result(before, after, placed, withTotals, drawn, moved, transfers, lost, lostAlt);
    }

    /** Riders on board between each stop and the next: seq -> load (seq is the segment's first stop). */
    public static Map<Integer, Double> loads(List<Leg> legs) {
        Map<Integer, Double> out = new TreeMap<>();
        if (legs == null) {
            return out;
        }
        for (Leg l : legs) {
            for (int s = l.boardSeq(); s < l.alightSeq(); s++) {
                out.merge(s, l.passengers(), Double::sum);
            }
        }
        return out;
    }

    public static double total(List<Leg> legs) {
        return legs == null ? 0 : legs.stream().mapToDouble(Leg::passengers).sum();
    }

    private static List<Leg> requireRoute(Map<String, List<Leg>> state, String route) {
        List<Leg> legs = route == null ? null : state.get(route);
        if (legs == null) {
            throw new IllegalArgumentException("no OD legs for route " + route);
        }
        return legs;
    }

    private static Leg with(Leg l, String route, double passengers) {
        return new Leg(route, l.boardArs(), l.alightArs(), l.boardSeq(), l.alightSeq(), passengers);
    }

    private static Map<String, List<Leg>> group(List<Leg> legs) {
        Map<String, List<Leg>> out = new LinkedHashMap<>();
        for (Leg l : legs) {
            out.computeIfAbsent(l.route(), k -> new ArrayList<>()).add(l);
        }
        return out;
    }
}
