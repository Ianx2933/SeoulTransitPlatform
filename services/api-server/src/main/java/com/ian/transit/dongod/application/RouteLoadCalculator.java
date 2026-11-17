package com.ian.transit.dongod.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts route OD legs on stop-to-stop segments.
 *
 * A leg (route, boarding ARS, alighting ARS, passengers) is placed on the
 * stored variant of its route that passes the boarding stop and then the
 * alighting stop; with several candidates (a stop served twice on a loop, or
 * a route stored as variants such as 2312A/2312B) the shortest span wins.
 * Every segment from the boarding stop to the one before the alighting stop
 * carries the leg's passengers. Legs that no variant can hold (no stored
 * path, or a stop missing from it) are counted as unplaced by route.
 *
 * Pure: no database, so it is unit tested directly.
 */
public final class RouteLoadCalculator {

    private RouteLoadCalculator() {
    }

    public record Leg(String route, String boardArs, String alightArs, double passengers) {
    }

    /** A stored station: TOPIS route, stored variant name, BIS sequence, ARS (any padding). */
    public record Station(String odRoute, String variant, int seq, String ars) {
    }

    /** Segment from station seq to the next station of a variant. */
    public record SegmentRef(String variant, int seq) {
    }

    public record Result(Map<SegmentRef, Double> loads, double placed, double unplaced,
                         Map<String, Double> unplacedByRoute) {
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

    private record Variant(String name, List<Integer> seqs, Map<String, List<Integer>> positions) {
    }

    public static Result compute(List<Leg> legs, List<Station> stations) {
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
                Map<String, List<Integer>> pos = new HashMap<>();
                for (int i = 0; i < ordered.size(); i++) {
                    seqs.add(ordered.get(i).seq());
                    String ars = pad(ordered.get(i).ars());
                    if (ars != null) {
                        pos.computeIfAbsent(ars, k -> new ArrayList<>()).add(i);
                    }
                }
                list.add(new Variant(name, seqs, pos));
            });
            variants.put(route, list);
        });

        Map<SegmentRef, Double> loads = new HashMap<>();
        Map<String, Double> unplacedByRoute = new LinkedHashMap<>();
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
            for (Variant v : variants.getOrDefault(leg.route(), List.of())) {
                List<Integer> froms = v.positions().get(b);
                List<Integer> tos = v.positions().get(a);
                if (froms == null || tos == null) {
                    continue;
                }
                for (int from : froms) {
                    for (int to : tos) {
                        if (to > from && (best == null || to - from < bestTo - bestFrom)) {
                            best = v;
                            bestFrom = from;
                            bestTo = to;
                        }
                    }
                }
            }
            if (best == null) {
                unplaced += leg.passengers();
                unplacedByRoute.merge(leg.route(), leg.passengers(), Double::sum);
                continue;
            }
            placed += leg.passengers();
            for (int k = bestFrom; k < bestTo; k++) {
                loads.merge(new SegmentRef(best.name(), best.seqs().get(k)), leg.passengers(), Double::sum);
            }
        }
        return new Result(loads, placed, unplaced, unplacedByRoute);
    }
}
