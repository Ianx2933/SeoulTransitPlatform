package com.ian.transit.curatedod.application;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.ian.transit.map.month.BusMonthDemandRepository;
import com.ian.transit.map.month.BusMonthDemandRepository.StopPoint;
import com.ian.transit.scenario.infrastructure.ScenarioQueryRepository;
import com.ian.transit.scenario.infrastructure.ScenarioQueryRepository.PairSegment;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
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
 */
@Service
public class StopPathService {

    public static final int MAX_STOPS = 240;
    /** A stored segment longer than this many times the straight distance is a detour of another route. */
    static final double MAX_DETOUR = 3.0;

    private final BusMonthDemandRepository stops;
    private final ScenarioQueryRepository segments;
    private final Cache<String, StopPath> cache = Caffeine.newBuilder()
            .maximumSize(500)
            .expireAfterWrite(Duration.ofHours(12))
            .build();

    public StopPathService(BusMonthDemandRepository stops, ScenarioQueryRepository segments) {
        this.stops = stops;
        this.segments = segments;
    }

    public record Stop(String ars, String name, double lat, double lng) {
    }

    public record Segment(String fromArs, String toArs, List<double[]> path, boolean straight, double lengthM) {
    }

    public record StopPath(List<Stop> stops, List<Segment> segments, int missingStops) {
    }

    public StopPath path(String stopList) {
        List<String> ars = parse(stopList);
        return cache.get(String.join(",", ars), k -> build(ars));
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
        return assemble(ars, stops.stopPoints(unique), segments.pairSegmentsWithHistory(unique));
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
        return new StopPath(outStops, out, missing);
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
