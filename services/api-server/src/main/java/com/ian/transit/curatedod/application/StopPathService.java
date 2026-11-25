package com.ian.transit.curatedod.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ian.transit.curatedod.infrastructure.RouteSnapshotRepository;
import com.ian.transit.curatedod.infrastructure.RouteSnapshotRepository.Snapshot;
import com.ian.transit.map.month.BusMonthDemandRepository;
import com.ian.transit.map.month.BusMonthDemandRepository.StopPoint;
import com.ian.transit.scenario.infrastructure.ScenarioQueryRepository;
import com.ian.transit.scenario.infrastructure.ScenarioQueryRepository.PairSegment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Rebuilds a route's line from a list of stops in riding order. A route that
 * ran differently in a past period (3411 before its change) has OD stops the
 * stored current path no longer passes, so the screen draws the line from the
 * OD stop order instead: the stored road segment between two stops where any
 * current route, or an earlier stored path of a changed route, runs it; a
 * straight line otherwise.
 *
 * Given the route and a day, the stop order comes from the Seoul route master
 * snapshot in force that day (bus_route_stop_snapshot) when there is one: it
 * has every stop the route served, with positions, also outside Seoul and for
 * withdrawn routes (1155). The OD stop order is the fallback.
 */
@Service
public class StopPathService {

    public static final int MAX_STOPS = 240;
    /** A stored segment longer than this many times the straight distance is a detour of another route. */
    static final double MAX_DETOUR = 3.0;
    /** Share of the OD stops a master snapshot must have to be taken as the route of that period. */
    static final double MIN_SNAPSHOT_SHARE = 0.9;

    private final BusMonthDemandRepository stops;
    private final ScenarioQueryRepository segments;
    private final RouteSnapshotRepository snapshots;
    private final Cache<String, StopPath> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(12))
            .build();

    public StopPathService(BusMonthDemandRepository stops, ScenarioQueryRepository segments,
                           RouteSnapshotRepository snapshots) {
        this.stops = stops;
        this.segments = segments;
        this.snapshots = snapshots;
    }

    public record Stop(String ars, String name, double lat, double lng) {
    }

    public record Segment(String fromArs, String toArs, List<double[]> path, boolean straight, double lengthM) {
    }

    /** snapshotDate: the route master snapshot the stop order came from, null for the OD order. */
    public record StopPath(List<Stop> stops, List<Segment> segments, int missingStops, LocalDate snapshotDate) {
        StopPath withSnapshot(LocalDate date) {
            return new StopPath(stops, segments, missingStops, date);
        }
    }

    public StopPath path(String stopList) {
        return path(stopList, null, null);
    }

    /** route and at (a day of the period) are optional; with both, the master snapshot of that day is tried first. */
    public StopPath path(String stopList, String route, LocalDate at) {
        List<String> ars = parse(stopList);
        String key = String.join(",", ars) + "|" + (route == null ? "" : route) + "|" + (at == null ? "" : at);
        return cache.get(key, k -> {
            if (route != null && !route.isBlank() && at != null) {
                Snapshot snap = snapshots.routeAt(routeNames(route), at);
                List<RouteSnapshotRepository.Row> order = snap == null ? List.of() : snapshotOrder(snap, ars);
                if (order.size() >= 2 && covers(order, ars)) {
                    return fromSnapshot(order).withSnapshot(snap.date());
                }
            }
            return build(ars);
        });
    }

    /** A stop's key for comparing stop lists: its padded ARS, or its name for a stop with no number. */
    static String arsKey(RouteHistoryService.StopRef s) {
        if (s.ars() == null || s.ars().isBlank()) {
            return "name:" + s.name();
        }
        String a = s.ars().trim();
        return a.matches("\\d{1,4}") ? "0".repeat(5 - a.length()) + a : a;
    }

    /** The name as given and without a TOPIS suffix ("5522A난곡" → "5522A", "8773출근" → "8773"). */
    static List<String> routeNames(String route) {
        String r = route.trim();
        String bare = r.replaceAll("[가-힣]+$", "");
        return bare.isEmpty() || bare.equals(r) ? List.of(r) : List.of(r, bare);
    }

    /**
     * The snapshot route (of the variants with this name) sharing the most stops
     * with the OD stops, in order, numbered stops only, a stop repeated back to back once.
     */
    static List<RouteSnapshotRepository.Row> snapshotOrder(Snapshot snap, List<String> odStops) {
        var od = new HashSet<>(odStops);
        List<RouteSnapshotRepository.Row> best = List.of();
        long bestShared = -1;
        for (var rows : snap.byRouteId().values()) {
            long shared = rows.stream().map(RouteSnapshotRepository.Row::ars).filter(od::contains).distinct().count();
            if (shared > bestShared) {
                bestShared = shared;
                best = rows;
            }
        }
        List<RouteSnapshotRepository.Row> out = new ArrayList<>();
        for (var r : best.stream().sorted(Comparator.comparingInt(RouteSnapshotRepository.Row::seq)).toList()) {
            if (r.ars() == null || r.ars().isBlank()) {
                continue;
            }
            if (!out.isEmpty() && out.get(out.size() - 1).ars().equals(r.ars())) {
                continue;
            }
            out.add(r);
        }
        return out.size() > MAX_STOPS ? out.subList(0, MAX_STOPS) : out;
    }

    /** The snapshot is the route the OD saw: most OD stops are on it (else the nearest snapshot is another version). */
    static boolean covers(List<RouteSnapshotRepository.Row> order, List<String> odStops) {
        var on = new HashSet<String>();
        order.forEach(r -> on.add(r.ars()));
        var od = new LinkedHashSet<>(odStops);
        long shared = od.stream().filter(on::contains).count();
        return shared >= MIN_SNAPSHOT_SHARE * od.size();
    }

    private StopPath fromSnapshot(List<RouteSnapshotRepository.Row> order) {
        List<String> ars = order.stream().map(RouteSnapshotRepository.Row::ars).toList();
        Map<String, StopPoint> points = new HashMap<>();
        for (var r : order) {
            points.putIfAbsent(r.ars(), new StopPoint(r.ars(), r.name(), r.lat(), r.lng()));
        }
        return assemble(ars, points, segments.pairSegmentsWithHistory(new LinkedHashSet<>(ars)));
    }

    /** Padded five-digit ARS numbers in the given order; repeats kept (a loop route passes a stop twice). */
    static List<String> parse(String stopList) {
        if (stopList == null || stopList.isBlank()) {
            throw new IllegalArgumentException("give stops as a comma-separated list of ARS numbers");
        }
        List<String> out = new ArrayList<>();
        for (String raw : stopList.split(",")) {
            String s = raw.trim();
            if (s.isEmpty()) {
                continue;
            }
            if (!s.matches("\\d{1,5}")) {
                throw new IllegalArgumentException("not an ARS number: " + s);
            }
            out.add("0".repeat(5 - s.length()) + s);
        }
        if (out.size() < 2) {
            throw new IllegalArgumentException("give at least two stops");
        }
        if (out.size() > MAX_STOPS) {
            throw new IllegalArgumentException("at most " + MAX_STOPS + " stops");
        }
        return out;
    }

    private StopPath build(List<String> ars) {
        var unique = new LinkedHashSet<>(ars);
        Map<String, StopPoint> points = new HashMap<>(stops.stopPoints(unique));
        var unplaced = unique.stream().filter(a -> !points.containsKey(a)).toList();
        if (!unplaced.isEmpty()) {
            // Stops outside Seoul are missing from the stop tables; take them from stored route paths.
            segments.stationPoints(unplaced).forEach((a, p) -> points.put(a, new StopPoint(a, p.name(), p.lat(), p.lng())));
            var still = unplaced.stream().filter(a -> !points.containsKey(a)).toList();
            if (!still.isEmpty()) {
                // Stops of withdrawn routes: the route master snapshots know them.
                snapshots.points(still).forEach((a, p) -> points.put(a, new StopPoint(a, p.name(), p.lat(), p.lng())));
            }
        }
        return assemble(ars, points, segments.pairSegmentsWithHistory(unique));
    }

    /** Pure: joins the stop points and stored pair segments into a line. */
    static StopPath assemble(List<String> ars, Map<String, StopPoint> points, Map<String, PairSegment> stored) {
        List<Stop> outStops = new ArrayList<>();
        int missing = 0;
        for (String a : ars) {
            StopPoint p = points.get(a);
            if (p == null) {
                missing++;
            } else {
                outStops.add(new Stop(a, p.name(), p.lat(), p.lng()));
            }
        }
        List<Segment> out = new ArrayList<>();
        StopPoint previous = null;
        for (String a : ars) {
            StopPoint p = points.get(a);
            if (p == null) {
                continue; // the next placed stop joins the last placed one
            }
            if (previous != null && !previous.ars().equals(p.ars())) {
                double straight = metres(previous, p);
                PairSegment seg = stored.get(previous.ars() + ">" + p.ars());
                if (seg != null && seg.path().size() >= 2 && seg.lengthM() <= Math.max(straight * MAX_DETOUR, 300)) {
                    out.add(new Segment(previous.ars(), p.ars(), seg.path(), false, Math.round(seg.lengthM())));
                } else {
                    out.add(new Segment(previous.ars(), p.ars(),
                            List.of(new double[] {previous.lat(), previous.lng()}, new double[] {p.lat(), p.lng()}),
                            true, Math.round(straight)));
                }
            }
            previous = p;
        }
        return new StopPath(outStops, out, missing, null);
    }

    static double metres(StopPoint a, StopPoint b) {
        double r = 6_371_000;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLng = Math.toRadians(b.lng() - a.lng());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(a.lat())) * Math.cos(Math.toRadians(b.lat())) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }
}
