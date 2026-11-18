package com.ian.transit.roadload.application;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.dongod.application.DongRouteLoadService;
import com.ian.transit.dongod.application.RouteLoadCalculator;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import com.ian.transit.roadload.dto.RoadLoadResponse;
import com.ian.transit.roadload.infrastructure.RoadLoadQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Riders on the road beyond one district: chosen routes added up (the route
 * analysis map in demand colours), the riders of one stop on every route, and
 * the riders who pass one road segment on any route (select link analysis),
 * with the stops they come from and go to. All use RouteLoadCalculator and
 * the stored route paths, like the district road view.
 */
@Service
@RequiredArgsConstructor
public class RoadLoadService {

    public static final int MAX_ROUTES = 20;
    public static final int MAX_POINTS = 150;

    private final RoadLoadQueryRepository repository;
    private final RouteLoadQueryRepository paths;

    /** Route names from "302, 440,333": trimmed, unique, in order. */
    static List<String> parseRoutes(String routes) {
        if (routes == null || routes.isBlank()) {
            throw new IllegalArgumentException("routes is required, e.g. routes=302,440");
        }
        List<String> out = new ArrayList<>(new LinkedHashSet<>(Arrays.stream(routes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList()));
        if (out.isEmpty() || out.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("give 1 to " + MAX_ROUTES + " routes");
        }
        return out;
    }

    static String requireArs(String ars, String name) {
        String padded = RouteLoadCalculator.pad(ars);
        if (padded == null || padded.length() != 5) {
            throw new IllegalArgumentException(name + " must be a 5-digit ARS number, got " + ars);
        }
        return padded;
    }

    private static String dayType(String label) {
        return label.startsWith("typical_") ? label.substring("typical_".length()) : label;
    }

    public RoadLoadResponse routes(String date, String routesParam) {
        List<String> routes = parseRoutes(routesParam);
        String label = TypicalDay.label(date);
        List<RouteLoadCalculator.Leg> legs = repository.legsByRoutes(label, routes);
        if (legs.isEmpty()) {
            throw new NotFoundException("No riders on " + String.join(", ", routes) + " on " + dayType(label));
        }
        return build("routes", String.join(", ", routes), label, legs, null, false);
    }

    public RoadLoadResponse stop(String date, String ars, String direction) {
        String stop = requireArs(ars, "ars");
        boolean alighting = "alight".equalsIgnoreCase(direction);
        if (direction != null && !direction.isBlank() && !alighting && !"board".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be board or alight, got " + direction);
        }
        String label = TypicalDay.label(date);
        List<RouteLoadCalculator.Leg> legs = repository.legsByStop(label, stop, alighting);
        if (legs.isEmpty()) {
            throw new NotFoundException("No bus riders " + (alighting ? "alighting" : "boarding") + " at " + stop);
        }
        RoadLoadQueryRepository.StopPoint point = repository.stopPoints(List.of(stop)).get(stop);
        String title = point != null ? point.name() + " (" + stop + ")" : stop;
        return build("stop", title, label, legs, null, true);
    }

    public RoadLoadResponse segment(String date, String from, String to) {
        String a = requireArs(from, "from");
        String b = requireArs(to, "to");
        String label = TypicalDay.label(date);
        List<String> routes = repository.routesThrough(a, b);
        if (routes.isEmpty()) {
            throw new NotFoundException("No stored route runs from " + a + " to " + b);
        }
        List<RouteLoadCalculator.Leg> legs = repository.legsByRoutes(label, routes);
        Map<String, RoadLoadQueryRepository.StopPoint> ends = repository.stopPoints(List.of(a, b));
        String title = nameOr(ends.get(a), a) + " → " + nameOr(ends.get(b), b);
        return build("segment", title, label, legs, (x, y) -> x.equals(a) && y.equals(b), true);
    }

    private static String nameOr(RoadLoadQueryRepository.StopPoint p, String ars) {
        return p != null ? p.name() : ars;
    }

    private RoadLoadResponse build(String kind, String title, String label, List<RouteLoadCalculator.Leg> legs,
                                   BiPredicate<String, String> mustCross, boolean withPoints) {
        Set<String> routes = legs.stream().map(RouteLoadCalculator.Leg::route).collect(Collectors.toCollection(TreeSet::new));
        RouteLoadCalculator.Result result = RouteLoadCalculator.compute(legs, paths.stations(routes), mustCross);
        Set<String> variants = result.loads().keySet().stream()
                .map(RouteLoadCalculator.SegmentRef::variant).collect(Collectors.toSet());
        List<DongRouteLoadResponse.Segment> segments = DongRouteLoadService.merge(result, paths.segments(variants));

        List<RoadLoadResponse.Point> origins = List.of();
        List<RoadLoadResponse.Point> destinations = List.of();
        if (withPoints) {
            Map<String, Double> byBoard = sum(result.placedLegs(), RouteLoadCalculator.PlacedLeg::boardArs);
            Map<String, Double> byAlight = sum(result.placedLegs(), RouteLoadCalculator.PlacedLeg::alightArs);
            Set<String> ars = new TreeSet<>(byBoard.keySet());
            ars.addAll(byAlight.keySet());
            Map<String, RoadLoadQueryRepository.StopPoint> points = repository.stopPoints(ars);
            origins = points(byBoard, points);
            destinations = points(byAlight, points);
        }
        List<DongRouteLoadResponse.Unplaced> unplaced = result.unplacedByRoute().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(DongRouteLoadService.MAX_UNPLACED_ROUTES)
                .map(e -> new DongRouteLoadResponse.Unplaced(e.getKey(), e.getValue()))
                .toList();
        List<String> usedRoutes = result.placedLegs().stream().map(RouteLoadCalculator.PlacedLeg::route)
                .collect(Collectors.toCollection(TreeSet::new)).stream().toList();
        return new RoadLoadResponse(kind, title, dayType(label), result.placed() + result.unplaced(),
                result.placed(), result.unplaced(), usedRoutes, segments, origins, destinations, unplaced);
    }

    static Map<String, Double> sum(List<RouteLoadCalculator.PlacedLeg> legs,
                                   Function<RouteLoadCalculator.PlacedLeg, String> key) {
        return legs.stream().filter(l -> key.apply(l) != null)
                .collect(Collectors.groupingBy(key, Collectors.summingDouble(RouteLoadCalculator.PlacedLeg::passengers)));
    }

    static List<RoadLoadResponse.Point> points(Map<String, Double> riders,
                                               Map<String, RoadLoadQueryRepository.StopPoint> where) {
        return riders.entrySet().stream()
                .filter(e -> where.containsKey(e.getKey()))
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_POINTS)
                .map(e -> {
                    RoadLoadQueryRepository.StopPoint p = where.get(e.getKey());
                    return new RoadLoadResponse.Point(p.ars(), p.name(), p.lat(), p.lng(), e.getValue());
                })
                .toList();
    }
}
