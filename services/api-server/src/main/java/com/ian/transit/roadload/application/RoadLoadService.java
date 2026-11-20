package com.ian.transit.roadload.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.dongod.application.DongRouteLoadService;
import com.ian.transit.dongod.application.RouteLoadCalculator;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import com.ian.transit.period.OdBasis;
import com.ian.transit.period.application.PeriodOdService;
import com.ian.transit.period.infrastructure.PeriodOdRepository.PeriodLeg;
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
 *
 * A period (BigQuery) replaces the typical day's legs, and boarding hours cut
 * every leg to the riders who boarded in them (PeriodOdService); the plain
 * typical day never reaches BigQuery.
 */
@Service
@RequiredArgsConstructor
public class RoadLoadService {

    public static final int MAX_ROUTES = 20;
    public static final int MAX_POINTS = 150;

    private final RoadLoadQueryRepository repository;
    private final RouteLoadQueryRepository paths;
    /** Only used for a period or hours; null in tests of the plain typical day. */
    private final PeriodOdService periodService;

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

    private static RouteLoadCalculator.Leg roadLeg(PeriodLeg l) {
        return new RouteLoadCalculator.Leg(l.route(), l.boardArs(), l.alightArs(), l.passengers(),
                l.boardSeq(), l.alightSeq());
    }

    /** The whole day's legs of the routes: typical day from Cloud SQL or a period from BigQuery. */
    private List<RouteLoadCalculator.Leg> legsByRoutes(OdBasis basis, List<String> routes) {
        return basis.typical()
                ? repository.legsByRoutes(basis.label(), routes)
                : periodService.legsByRoutes(basis, routes).stream().map(RoadLoadService::roadLeg).toList();
    }

    private List<RouteLoadCalculator.Leg> legsByStop(OdBasis basis, String stop, boolean alighting) {
        return basis.typical()
                ? repository.legsByStop(basis.label(), stop, alighting)
                : periodService.legsByStops(basis, List.of(stop, stop.replaceFirst("^0+", "")), alighting)
                        .stream().map(RoadLoadService::roadLeg).toList();
    }

    /** Legs cut to the boarding hours (PeriodOdService); the legs themselves without hours. */
    public static List<RouteLoadCalculator.Leg> split(PeriodOdService periods, OdBasis basis,
                                                       List<RouteLoadCalculator.Leg> legs) {
        if (basis.hours() == null) {
            return legs;
        }
        return periods.splitByHours(legs, basis.dayType(), basis.hours(),
                RouteLoadCalculator.Leg::route, RouteLoadCalculator.Leg::boardArs, RouteLoadCalculator.Leg::passengers,
                RoadLoadService::withPassengers);
    }

    static RouteLoadCalculator.Leg withPassengers(RouteLoadCalculator.Leg l, double p) {
        return new RouteLoadCalculator.Leg(l.route(), l.boardArs(), l.alightArs(), p, l.boardSeq(), l.alightSeq());
    }

    /**
     * With boarding hours, the busiest merged segment of the busiest single hour
     * of the day (one colour scale for every hour); null without hours.
     */
    public static Double legendMax(PeriodOdService periods, OdBasis basis, List<RouteLoadCalculator.Leg> dayLegs,
                                   List<RouteLoadCalculator.Station> stations,
                                   BiPredicate<String, String> mustCross,
                                   List<RouteLoadQueryRepository.SegmentRow> rows) {
        if (basis.hours() == null) {
            return null;
        }
        return periods.legendMax(dayLegs, basis.dayType(),
                RouteLoadCalculator.Leg::route, RouteLoadCalculator.Leg::boardArs, RouteLoadCalculator.Leg::passengers,
                RoadLoadService::withPassengers,
                hour -> DongRouteLoadService.merge(RouteLoadCalculator.compute(hour, stations, mustCross), rows)
                        .stream().mapToDouble(DongRouteLoadResponse.Segment::load).max().orElse(0));
    }

    public RoadLoadResponse routes(String date, String routesParam) {
        return routes(OdBasis.of(date, null, null, null), routesParam);
    }

    public RoadLoadResponse stop(String date, String ars, String direction) {
        return stop(OdBasis.of(date, null, null, null), ars, direction);
    }

    public RoadLoadResponse segment(String date, String from, String to) {
        return segment(OdBasis.of(date, null, null, null), from, to);
    }

    public RoadLoadResponse routes(OdBasis basis, String routesParam) {
        List<String> routes = parseRoutes(routesParam);
        List<RouteLoadCalculator.Leg> legs = legsByRoutes(basis, routes);
        if (legs.isEmpty()) {
            throw new NotFoundException("No riders on " + String.join(", ", routes) + " on " + basis);
        }
        return build("routes", String.join(", ", routes), basis, legs, null, false);
    }

    public RoadLoadResponse stop(OdBasis basis, String ars, String direction) {
        String stop = requireArs(ars, "ars");
        boolean alighting = "alight".equalsIgnoreCase(direction);
        if (direction != null && !direction.isBlank() && !alighting && !"board".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be board or alight, got " + direction);
        }
        List<RouteLoadCalculator.Leg> legs = legsByStop(basis, stop, alighting);
        if (legs.isEmpty()) {
            throw new NotFoundException("No bus riders " + (alighting ? "alighting" : "boarding") + " at " + stop);
        }
        RoadLoadQueryRepository.StopPoint point = repository.stopPoints(List.of(stop)).get(stop);
        String title = point != null ? point.name() + " (" + stop + ")" : stop;
        return build("stop", title, basis, legs, null, true);
    }

    public RoadLoadResponse segment(OdBasis basis, String from, String to) {
        String a = requireArs(from, "from");
        String b = requireArs(to, "to");
        List<String> routes = repository.routesThrough(a, b);
        if (routes.isEmpty()) {
            throw new NotFoundException("No stored route runs from " + a + " to " + b);
        }
        List<RouteLoadCalculator.Leg> legs = legsByRoutes(basis, routes);
        Map<String, RoadLoadQueryRepository.StopPoint> ends = repository.stopPoints(List.of(a, b));
        String title = nameOr(ends.get(a), a) + " → " + nameOr(ends.get(b), b);
        return build("segment", title, basis, legs, (x, y) -> x.equals(a) && y.equals(b), true);
    }

    private static String nameOr(RoadLoadQueryRepository.StopPoint p, String ars) {
        return p != null ? p.name() : ars;
    }

    private RoadLoadResponse build(String kind, String title, OdBasis basis, List<RouteLoadCalculator.Leg> dayLegs,
                                   BiPredicate<String, String> mustCross, boolean withPoints) {
        List<RouteLoadCalculator.Leg> legs = split(periodService, basis, dayLegs);
        if (legs.isEmpty()) {
            throw new NotFoundException("No riders for " + title + " in " + basis);
        }
        Set<String> routes = dayLegs.stream().map(RouteLoadCalculator.Leg::route).collect(Collectors.toCollection(TreeSet::new));
        List<RouteLoadCalculator.Station> stations = paths.stations(routes);
        RouteLoadCalculator.Result result = RouteLoadCalculator.compute(legs, stations, mustCross);
        // Variants of the whole day, so every hour of the fixed legend finds its segments.
        RouteLoadCalculator.Result day = legs == dayLegs ? result : RouteLoadCalculator.compute(dayLegs, stations, mustCross);
        Set<String> variants = day.loads().keySet().stream()
                .map(RouteLoadCalculator.SegmentRef::variant).collect(Collectors.toSet());
        List<RouteLoadQueryRepository.SegmentRow> rows = paths.segments(variants);
        List<DongRouteLoadResponse.Segment> segments = DongRouteLoadService.merge(result, rows);
        Double legendMax = legendMax(periodService, basis, dayLegs, stations, mustCross, rows);

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
        return new RoadLoadResponse(kind, title, basis.dayType(), result.placed() + result.unplaced(),
                result.placed(), result.unplaced(), usedRoutes, segments, origins, destinations, unplaced, legendMax);
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
