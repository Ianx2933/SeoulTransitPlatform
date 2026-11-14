package com.ian.transit.curatedod.application;

import com.ian.transit.curatedod.dto.RouteSegmentResponse;
import com.ian.transit.curatedod.dto.RouteStopDetailResponse;
import com.ian.transit.curatedod.dto.RouteStopsResponse;
import com.ian.transit.curatedod.dto.StopFlow;
import com.ian.transit.curatedod.dto.StopLoad;
import com.ian.transit.curatedod.dto.StopReach;
import com.ian.transit.curatedod.dto.SegmentStop;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Route analysis over one route's typical-day OD, computed in memory.
 *
 * A route has a few thousand OD pairs on a typical day, so the whole route is
 * read once and every figure below is derived from the same rows. Stops are
 * identified by their sequence on the route: a stop the route serves twice (a
 * loop, a depot) has two sequences and is two stops here.
 *
 * Rows whose alighting sequence is not after the boarding sequence cannot be
 * placed on the route. They count in the route total and are reported as
 * unordered passengers, but not in any load or segment figure.
 */
public final class RouteOdAnalyzer {

    /** One OD pair of the route. */
    public record Leg(int boardSeq, String boardArs, String boardName,
                      int alightSeq, String alightArs, String alightName, double passengers) {
    }

    private record Stop(int seq, String ars, String name) {
    }

    public static final int TOP = 10;
    public static final List<Double> REACH_LEVELS = List.of(25.0, 50.0, 100.0, 200.0);

    private final String label;
    private final String route;
    private final Integer nDays;
    private final List<Leg> ordered = new ArrayList<>();
    private final TreeMap<Integer, Stop> stops = new TreeMap<>();
    private double total;
    private double unordered;

    public RouteOdAnalyzer(String label, String route, Integer nDays, List<Leg> legs) {
        if (legs.isEmpty()) {
            throw new IllegalArgumentException("legs must not be empty");
        }
        this.label = label;
        this.route = route;
        this.nDays = nDays;
        for (Leg leg : legs) {
            total += leg.passengers();
            stops.putIfAbsent(leg.boardSeq(), new Stop(leg.boardSeq(), leg.boardArs(), leg.boardName()));
            stops.putIfAbsent(leg.alightSeq(), new Stop(leg.alightSeq(), leg.alightArs(), leg.alightName()));
            if (leg.alightSeq() > leg.boardSeq()) {
                ordered.add(leg);
            } else {
                unordered += leg.passengers();
            }
        }
    }

    public boolean hasStop(int seq) {
        return stops.containsKey(seq);
    }

    /** Boardings, alightings and riders on board after every stop. */
    public RouteStopsResponse stops() {
        Map<Integer, Double> on = new HashMap<>();
        Map<Integer, Double> off = new HashMap<>();
        for (Leg leg : ordered) {
            on.merge(leg.boardSeq(), leg.passengers(), Double::sum);
            off.merge(leg.alightSeq(), leg.passengers(), Double::sum);
        }
        List<StopLoad> rows = new ArrayList<>();
        double load = 0;
        for (Stop stop : stops.values()) {
            double boardings = on.getOrDefault(stop.seq(), 0.0);
            double alightings = off.getOrDefault(stop.seq(), 0.0);
            load += boardings - alightings;
            rows.add(new StopLoad(stop.seq(), stop.ars(), stop.name(), boardings, alightings, Math.max(load, 0.0)));
        }
        return new RouteStopsResponse(label, route, nDays, total, unordered, rows);
    }

    /**
     * Demand of the segment from stop {@code from} to stop {@code to}: trips with
     * both ends inside it, trips that start inside and leave past {@code to},
     * trips that enter from before {@code from} and end inside, and trips that
     * pass through without stopping inside.
     */
    public RouteSegmentResponse segment(int from, int to) {
        requireStop(from);
        requireStop(to);
        if (from >= to) {
            throw new IllegalArgumentException("from must come before to on the route (sequence "
                    + from + " is not before " + to + ")");
        }
        double within = 0, leaving = 0, entering = 0, through = 0;
        Map<Integer, Double> beyond = new HashMap<>();
        Map<Integer, Double> before = new HashMap<>();
        Map<Integer, Double> leavingBoard = new HashMap<>();
        for (Leg leg : ordered) {
            boolean boardsIn = leg.boardSeq() >= from && leg.boardSeq() <= to;
            boolean alightsIn = leg.alightSeq() >= from && leg.alightSeq() <= to;
            double p = leg.passengers();
            if (boardsIn && alightsIn) {
                within += p;
            } else if (boardsIn && leg.alightSeq() > to) {
                leaving += p;
                beyond.merge(leg.alightSeq(), p, Double::sum);
                leavingBoard.merge(leg.boardSeq(), p, Double::sum);
            } else if (leg.boardSeq() < from && alightsIn) {
                entering += p;
                before.merge(leg.boardSeq(), p, Double::sum);
            } else if (leg.boardSeq() < from && leg.alightSeq() > to) {
                through += p;
            }
        }
        RouteStopsResponse all = stops();
        List<SegmentStop> inside = new ArrayList<>();
        double leavingOnBoard = 0;
        for (StopLoad stop : all.stops()) {
            if (stop.seq() < from || stop.seq() > to) {
                continue;
            }
            double here = leavingBoard.getOrDefault(stop.seq(), 0.0);
            leavingOnBoard += here;
            inside.add(new SegmentStop(stop.seq(), stop.ars(), stop.name(), stop.boardings(),
                    stop.alightings(), stop.onBoardAfter(), here, leavingOnBoard));
        }
        Stop a = stops.get(from);
        Stop b = stops.get(to);
        return new RouteSegmentResponse(label, route, from, a.name(), to, b.name(), total,
                within, leaving, entering, through, inside, top(beyond), top(before));
    }

    /**
     * Where the riders of one stop go and come from, and how far the riders on
     * board as the bus leaves it carry: for each level, the farthest stop after
     * which more than that many of them are still on board.
     */
    public RouteStopDetailResponse stop(int seq) {
        requireStop(seq);
        Map<Integer, Double> destinations = new HashMap<>();
        Map<Integer, Double> origins = new HashMap<>();
        double boardings = 0, alightings = 0, onBoard = 0;
        TreeMap<Integer, Double> cohortByAlight = new TreeMap<>();
        for (Leg leg : ordered) {
            double p = leg.passengers();
            if (leg.boardSeq() == seq) {
                boardings += p;
                destinations.merge(leg.alightSeq(), p, Double::sum);
            }
            if (leg.alightSeq() == seq) {
                alightings += p;
                origins.merge(leg.boardSeq(), p, Double::sum);
            }
            if (leg.boardSeq() <= seq && leg.alightSeq() > seq) {
                onBoard += p;
                cohortByAlight.merge(leg.alightSeq(), p, Double::sum);
            }
        }
        List<StopReach> reach = new ArrayList<>();
        for (double level : REACH_LEVELS) {
            // Walk forward: riders still on board after stop j are the cohort
            // minus those who alighted at or before j.
            Stop until = null;
            double remaining = onBoard;
            if (remaining > level) {
                until = stops.get(seq);
                for (Stop next : stops.tailMap(seq, false).values()) {
                    remaining -= cohortByAlight.getOrDefault(next.seq(), 0.0);
                    if (remaining > level) {
                        until = next;
                    } else {
                        break;
                    }
                }
            }
            reach.add(until == null
                    ? new StopReach(level, null, null, null)
                    : new StopReach(level, until.seq(), until.ars(), until.name()));
        }
        Stop stop = stops.get(seq);
        return new RouteStopDetailResponse(label, route, seq, stop.ars(), stop.name(), boardings,
                alightings, onBoard, top(destinations), top(origins), reach);
    }

    private List<StopFlow> top(Map<Integer, Double> bySeq) {
        return bySeq.entrySet().stream()
                .sorted(Map.Entry.<Integer, Double>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(TOP)
                .map(e -> {
                    Stop s = stops.get(e.getKey());
                    return new StopFlow(s.seq(), s.ars(), s.name(), e.getValue());
                })
                .toList();
    }

    private void requireStop(int seq) {
        if (!stops.containsKey(seq)) {
            throw new IllegalArgumentException("route " + route + " has no stop with sequence " + seq);
        }
    }
}
