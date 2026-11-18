package com.ian.transit.dongod.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

/**
 * Puts route OD legs on stop-to-stop segments.
 *
 * A leg (route, boarding ARS, alighting ARS, passengers) is placed on the
 * stored variant of its route that passes the boarding stop and then the
 * alighting stop; with several candidates (a stop served twice on a loop, or
 * a route stored as variants such as 2312A/2312B) the shortest span wins.
 * When the leg carries TOPIS stop sequences and a stop occurs more than once
 * on a variant, the occurrences closest to those sequences are used instead:
 * on 6411 the stop 17104 is served twice, and riders who stay on through the
 * loop were put on the short span (2026-11-18, 390 riders a day short over 92
 * segments against the route chart).
 * Riders boarding at TOPIS's virtual depot stop (ARS 00000, sequence 0) are
 * placed from the first stored station (143: 448 riders a day were unplaced).
 * Every segment from the boarding stop to the one before the alighting stop
 * carries the leg's passengers. Legs that no variant can hold (no stored
 * path, or a stop missing from it) are counted as unplaced by route.
 *
 * With mustCross set, only legs whose span crosses a matching stop pair are
 * kept (select link analysis: the riders who pass one road segment); the rest
 * are left out, not counted as unplaced.
 *
 * Pure: no database, so it is unit tested directly.
 */
public final class RouteLoadCalculator {

    private RouteLoadCalculator() {
    }

    /** A route OD leg; boardSeq and alightSeq are TOPIS stop sequences, null when unknown. */
    public record Leg(String route, String boardArs, String alightArs, double passengers,
                      Integer boardSeq, Integer alightSeq) {

        public Leg(String route, String boardArs, String alightArs, double passengers) {
            this(route, boardArs, alightArs, passengers, null, null);
        }
    }

    /** A stored station: TOPIS route, stored variant name, BIS sequence, ARS (any padding). */
    public record Station(String odRoute, String variant, int seq, String ars) {
    }

    /** Segment from station seq to the next station of a variant. */
    public record SegmentRef(String variant, int seq) {
    }

    /** A leg that was placed, with padded ARS numbers. */
    public record PlacedLeg(String route, String boardArs, String alightArs, double passengers) {
    }

    public record Result(Map<SegmentRef, Double> loads, double placed, double unplaced,
                         Map<String, Double> unplacedByRoute, List<PlacedLeg> placedLegs) {

        public Result(Map<SegmentRef, Double> loads, double placed, double unplaced,
                      Map<String, Double> unplacedByRoute) {
            this(loads, placed, unplaced, unplacedByRoute, List.of());
        }
    }

    /** Five-digit ARS, or null for blank and zero. */
    public static String pad(String ars) {
        if (ars == null) {
            return null;
        }
        String s = ars.trim();
        if (s.isEmpty() || !s.chars().allMatch(Character::isDigit) || s.chars().allMatch(c -> c == '0')) {
            return null;
        }
        return s.length() >= 5 ? s : "0".repeat(5 - s.length()) + s;
    }

    private record Variant(String name, List<Integer> seqs, List<String> ars, Map<String, List<Integer>> positions) {
    }

    public static Result compute(List<Leg> legs, List<Station> stations) {
        return compute(legs, stations, null);
    }

    public static Result compute(List<Leg> legs, List<Station> stations, BiPredicate<String, String> mustCross) {
        Map<String, Map<String, List<Station>>> byRoute = new HashMap<>();
        for (Station s : stations) {
            byRoute.computeIfAbsent(s.odRoute(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(s.variant(), k -> new ArrayList<>()).add(s);
        }
        Map<String, List<Variant>> variants = new HashMap<>();
        byRoute.forEach((route, vs) -> {
            List<Variant> list = new ArrayList<>();
            vs.forEach((name, ss) -> {
                List<Station> ordered = new ArrayList<>(ss);
                ordered.sort((a, b) -> Integer.compare(a.seq(), b.seq()));
                List<Integer> seqs = new ArrayList<>();
                List<String> arsList = new ArrayList<>();
                Map<String, List<Integer>> pos = new HashMap<>();
                for (int i = 0; i < ordered.size(); i++) {
                    seqs.add(ordered.get(i).seq());
                    String ars = pad(ordered.get(i).ars());
                    arsList.add(ars);
                    if (ars != null) {
                        pos.computeIfAbsent(ars, k -> new ArrayList<>()).add(i);
                    }
                }
                list.add(new Variant(name, seqs, arsList, pos));
            });
            variants.put(route, list);
        });

        Map<SegmentRef, Double> loads = new HashMap<>();
        Map<String, Double> unplacedByRoute = new LinkedHashMap<>();
        List<PlacedLeg> placedLegs = new ArrayList<>();
        double placed = 0;
        double unplaced = 0;
        for (Leg leg : legs) {
            if (leg.passengers() <= 0) {
                continue;
            }
            String b = pad(leg.boardArs());
            String a = pad(leg.alightArs());
            Variant best = null;
            int bestFrom = -1;
            int bestTo = -1;
            boolean withSeq = leg.boardSeq() != null && leg.alightSeq() != null;
            // TOPIS's virtual stop "…(가상)": ARS 00000 at sequence 0, the depot
            // before the first stop. Its riders ride from the first stored station.
            boolean fromDepot = leg.boardSeq() != null && leg.boardSeq() == 0;
            for (Variant v : variants.getOrDefault(leg.route(), List.of())) {
                List<Integer> froms = b == null && fromDepot ? List.of(0) : v.positions().get(b);
                List<Integer> tos = v.positions().get(a);
                if (froms == null || tos == null) {
                    continue;
                }
                boolean bySeq = withSeq && (froms.size() > 1 || tos.size() > 1);
                int vFrom = -1;
                int vTo = -1;
                long vScore = Long.MAX_VALUE;
                for (int from : froms) {
                    for (int to : tos) {
                        if (to <= from) {
                            continue;
                        }
                        long score = bySeq
                                ? Math.abs((long) v.seqs().get(from) - leg.boardSeq())
                                        + Math.abs((long) v.seqs().get(to) - leg.alightSeq())
                                : to - from;
                        if (score < vScore || (score == vScore && to - from < vTo - vFrom)) {
                            vScore = score;
                            vFrom = from;
                            vTo = to;
                        }
                    }
                }
                if (vFrom >= 0 && (best == null || vTo - vFrom < bestTo - bestFrom)) {
                    best = v;
                    bestFrom = vFrom;
                    bestTo = vTo;
                }
            }
            if (best == null) {
                if (mustCross == null) {
                    unplaced += leg.passengers();
                    unplacedByRoute.merge(leg.route(), leg.passengers(), Double::sum);
                }
                continue;
            }
            if (mustCross != null && !crosses(best, bestFrom, bestTo, mustCross)) {
                continue;
            }
            placed += leg.passengers();
            placedLegs.add(new PlacedLeg(leg.route(), b, a, leg.passengers()));
            for (int k = bestFrom; k < bestTo; k++) {
                loads.merge(new SegmentRef(best.name(), best.seqs().get(k)), leg.passengers(), Double::sum);
            }
        }
        return new Result(loads, placed, unplaced, unplacedByRoute, placedLegs);
    }

    private static boolean crosses(Variant v, int from, int to, BiPredicate<String, String> pair) {
        for (int k = from; k < to; k++) {
            String x = v.ars().get(k);
            String y = v.ars().get(k + 1);
            if (x != null && y != null && pair.test(x, y)) {
                return true;
            }
        }
        return false;
    }
}
