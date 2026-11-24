package com.ian.transit.scenario.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.curatedod.application.RouteNameResolver;
import com.ian.transit.dongod.application.DongRouteLoadService;
import com.ian.transit.dongod.application.RouteLoadCalculator;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import com.ian.transit.period.OdBasis;
import com.ian.transit.period.application.PeriodOdService;
import com.ian.transit.roadload.application.RoadLoadService;
import com.ian.transit.roadload.infrastructure.RoadLoadQueryRepository;
import com.ian.transit.scenario.application.ScenarioEngine.Kind;
import com.ian.transit.scenario.application.ScenarioEngine.Leg;
import com.ian.transit.scenario.dto.ScenarioRequest;
import com.ian.transit.scenario.dto.ScenarioResponse;
import com.ian.transit.scenario.infrastructure.ScenarioQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Loads the legs a scenario touches, runs {@link ScenarioEngine} and adds the
 * road view and vehicle counts. Read only: nothing is stored.
 *
 * Legs come from the representative day (Cloud SQL), cut to the boarding hours
 * when given (PeriodOdService, as every other view). A period of months is not
 * offered yet. The routes read are the operations' routes and every stored
 * route that serves two or more stops of a drawn route.
 */
@Service
@RequiredArgsConstructor
public class ScenarioService {

    public static final int MAX_OPERATIONS = 20;
    public static final int MAX_STOPS = 150;
    public static final int MAX_ROUTES = 80;
    static final double DEFAULT_SPEED_KMH = 15;
    static final double DEFAULT_SHORT_TURN_SHARE = 0.5;
    /** Straight-line length to road length where no stored path links two stops. */
    static final double DETOUR = 1.3;
    /** Smallest change in riders on a road segment of an untouched route that is reported. */
    static final double ROAD_CHANGE_MIN = 0.5;

    private final RouteNameResolver names;
    private final RoadLoadQueryRepository legRepository;
    private final RouteLoadQueryRepository pathRepository;
    private final ScenarioQueryRepository scenarioRepository;
    private final PeriodOdService periodService;

    public ScenarioResponse evaluate(ScenarioRequest request) {
        if (request == null || request.operations() == null || request.operations().isEmpty()) {
            throw new IllegalArgumentException("give at least one operation");
        }
        if (request.operations().size() > MAX_OPERATIONS) {
            throw new IllegalArgumentException("at most " + MAX_OPERATIONS + " operations");
        }
        OdBasis basis = OdBasis.of(request.date(), null, request.hours(), null);
        double speed = request.speedKmh() != null ? request.speedKmh() : DEFAULT_SPEED_KMH;
        if (speed < 3 || speed > 80) {
            throw new IllegalArgumentException("speedKmh must be between 3 and 80");
        }
        List<ScenarioEngine.Operation> ops = new ArrayList<>();
        for (ScenarioRequest.Operation o : request.operations()) {
            ops.add(toOperation(o));
        }

        Set<String> routes = new LinkedHashSet<>();
        Set<String> drawnStops = new LinkedHashSet<>();
        for (ScenarioEngine.Operation op : ops) {
            if (op.kind() == Kind.NEW_ROUTE) {
                drawnStops.addAll(op.stops());
            } else {
                routes.add(op.route());
            }
        }
        // Stored routes serving two or more stops of a drawn route carry the riders it can take.
        Map<String, Set<String>> stopsByRoute = new HashMap<>();
        List<ScenarioQueryRepository.StationAt> drawnStations = scenarioRepository.stationsAt(drawnStops);
        for (ScenarioQueryRepository.StationAt s : drawnStations) {
            stopsByRoute.computeIfAbsent(s.odRoute(), k -> new HashSet<>()).add(RouteLoadCalculator.pad(s.ars()));
        }
        for (ScenarioEngine.Operation op : ops) {
            if (op.kind() != Kind.NEW_ROUTE) {
                continue;
            }
            Set<String> own = new HashSet<>(op.stops());
            stopsByRoute.forEach((route, stops) -> {
                if (stops.stream().filter(own::contains).count() >= 2) {
                    routes.add(route);
                }
            });
        }
        if (routes.size() > MAX_ROUTES) {
            throw new IllegalArgumentException("the scenario touches " + routes.size()
                    + " routes; at most " + MAX_ROUTES + " (draw a shorter route)");
        }

        List<RouteLoadCalculator.Leg> dayLegs = routes.isEmpty() ? List.of()
                : legRepository.legsByRoutes(basis.label(), routes);
        List<RouteLoadCalculator.Leg> roadLegs = RoadLoadService.split(periodService, basis, dayLegs);
        List<Leg> legs = new ArrayList<>();
        for (RouteLoadCalculator.Leg l : roadLegs) {
            String b = RouteLoadCalculator.pad(l.boardArs());
            String a = RouteLoadCalculator.pad(l.alightArs());
            if (b == null || a == null || l.boardSeq() == null || l.alightSeq() == null || l.passengers() <= 0) {
                continue;
            }
            legs.add(new Leg(l.route(), b, a, l.boardSeq(), l.alightSeq(), l.passengers()));
        }
        for (ScenarioEngine.Operation op : ops) {
            if (op.kind() != Kind.NEW_ROUTE && legs.stream().noneMatch(l -> l.route().equals(op.route()))) {
                throw new NotFoundException("No OD for route " + op.route() + " on " + basis);
            }
        }

        // Which stored routes link two stops in order: drawn stops and the stops of cut routes.
        Set<String> pairStops = new HashSet<>(drawnStops);
        for (ScenarioEngine.Operation op : ops) {
            if (op.kind() == Kind.CUT) {
                legs.stream().filter(l -> l.route().equals(op.route())).forEach(l -> {
                    pairStops.add(l.boardArs());
                    pairStops.add(l.alightArs());
                });
            }
        }
        List<ScenarioQueryRepository.StationAt> pairStations = pairStops.equals(drawnStops)
                ? drawnStations : scenarioRepository.stationsAt(pairStops);
        ScenarioEngine.PairService pairs = linking(pairStations);

        ScenarioEngine.Result result = ScenarioEngine.apply(legs, ops, pairs);

        List<ScenarioResponse.RouteResult> routeResults = new ArrayList<>();
        result.changes().forEach((route, c) -> routeResults.add(routeResult(c, result)));

        Map<String, RoadLoadQueryRepository.StopPoint> points = legRepository.stopPoints(drawnStops);
        Map<String, ScenarioQueryRepository.PairSegment> pairSegments = scenarioRepository.pairSegments(drawnStops);
        List<ScenarioResponse.RoadSegment> road = road(result, routes, ops, points, pairSegments);
        List<ScenarioResponse.NewRoute> newRoutes = new ArrayList<>();
        for (ScenarioEngine.Operation op : ops) {
            if (op.kind() == Kind.NEW_ROUTE) {
                newRoutes.add(newRoute(op, points, pairSegments, speed));
            }
        }

        ScenarioResponse.Summary summary = new ScenarioResponse.Summary(r1(result.moved()),
                r1(result.newTransfers()), r1(result.lost()), r1(result.lostWithAlternative()));
        ScenarioResponse.Assumptions assumptions = new ScenarioResponse.Assumptions(speed,
                "1/(k+1): k = stored routes linking the two stops in order, unless a share is given",
                DEFAULT_SHORT_TURN_SHARE,
                "new demand, riders from the subway and route choice are not modelled");
        return new ScenarioResponse(basis.label(), basis.hours() == null ? null : basis.hours().toString(),
                summary, routeResults, road, newRoutes, assumptions);
    }

    private ScenarioEngine.Operation toOperation(ScenarioRequest.Operation o) {
        if (o == null || o.type() == null) {
            throw new IllegalArgumentException("each operation needs a type");
        }
        Kind kind;
        try {
            kind = Kind.valueOf(o.type().trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("type must be SHORT_TURN, SPLIT, CUT or NEW_ROUTE, got " + o.type());
        }
        Double share = o.share();
        if (share != null && (share <= 0 || share > 1)) {
            throw new IllegalArgumentException("share must be above 0 and at most 1");
        }
        if (kind == Kind.NEW_ROUTE) {
            if (o.stops() == null || o.stops().size() < 2 || o.stops().size() > MAX_STOPS) {
                throw new IllegalArgumentException("a new route needs 2 to " + MAX_STOPS + " stops");
            }
            List<String> stops = new ArrayList<>();
            for (String s : o.stops()) {
                String padded = RouteLoadCalculator.pad(s);
                if (padded == null || padded.length() != 5) {
                    throw new IllegalArgumentException("stops must be 5-digit ARS numbers, got " + s);
                }
                stops.add(padded);
            }
            if (o.headwayMin() != null && (o.headwayMin() < 1 || o.headwayMin() > 180)) {
                throw new IllegalArgumentException("headwayMin must be between 1 and 180");
            }
            String name = o.name() == null || o.name().isBlank() ? "새 노선" : o.name().trim();
            if (name.length() > 40) {
                throw new IllegalArgumentException("a route name has at most 40 characters");
            }
            return new ScenarioEngine.Operation(kind, null, null, null, null, share, name, stops, o.headwayMin());
        }
        if (o.route() == null || o.route().isBlank()) {
            throw new IllegalArgumentException(kind + " needs a route");
        }
        String route = names.resolve(o.route());
        switch (kind) {
            case SPLIT -> {
                if (o.atSeq() == null) {
                    throw new IllegalArgumentException("SPLIT needs atSeq");
                }
            }
            case SHORT_TURN, CUT -> {
                if (o.fromSeq() == null || o.toSeq() == null || o.fromSeq() >= o.toSeq()) {
                    throw new IllegalArgumentException(kind + " needs fromSeq below toSeq");
                }
            }
            default -> {
            }
        }
        return new ScenarioEngine.Operation(kind, route, o.fromSeq(), o.toSeq(), o.atSeq(), share,
                kind == Kind.SHORT_TURN ? o.name() : null, null, null);
    }

    /** Stored routes with a variant passing the first stop before the second. */
    static ScenarioEngine.PairService linking(List<ScenarioQueryRepository.StationAt> stations) {
        Map<String, Map<String, List<Integer>>> byVariant = new HashMap<>();
        Map<String, String> routeOf = new HashMap<>();
        for (ScenarioQueryRepository.StationAt s : stations) {
            String ars = RouteLoadCalculator.pad(s.ars());
            if (ars == null) {
                continue;
            }
            byVariant.computeIfAbsent(s.variant(), k -> new HashMap<>())
                    .computeIfAbsent(ars, k -> new ArrayList<>()).add(s.seq());
            routeOf.put(s.variant(), s.odRoute());
        }
        Map<String, Set<String>> memo = new HashMap<>();
        return (b, a) -> memo.computeIfAbsent(b + ">" + a, key -> {
            Set<String> out = new TreeSet<>();
            byVariant.forEach((variant, seqs) -> {
                List<Integer> from = seqs.get(b);
                List<Integer> to = seqs.get(a);
                if (from == null || to == null) {
                    return;
                }
                int first = from.stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
                if (to.stream().anyMatch(t -> t > first)) {
                    out.add(routeOf.get(variant));
                }
            });
            return out;
        });
    }

    private static ScenarioResponse.RouteResult routeResult(ScenarioEngine.RouteChange c,
                                                            ScenarioEngine.Result result) {
        List<Leg> before = result.before().getOrDefault(c.route(), List.of());
        List<Leg> after = result.after().getOrDefault(c.route(), List.of());
        Map<Integer, Double> lb = ScenarioEngine.loads(before);
        Map<Integer, Double> la = ScenarioEngine.loads(after);
        Map<Integer, String> arsOf = new HashMap<>();
        List<String> drawn = result.drawnStops().get(c.route());
        if (drawn != null) {
            for (int i = 0; i < drawn.size(); i++) {
                arsOf.put(i + 1, drawn.get(i));
            }
        }
        for (List<Leg> list : List.of(before, after)) {
            for (Leg l : list) {
                arsOf.putIfAbsent(l.boardSeq(), l.boardArs());
                arsOf.putIfAbsent(l.alightSeq(), l.alightArs());
            }
        }
        Set<Integer> seqs = new TreeSet<>(lb.keySet());
        seqs.addAll(la.keySet());
        List<ScenarioResponse.Load> loads = new ArrayList<>();
        for (int s : seqs) {
            loads.add(new ScenarioResponse.Load(s, arsOf.get(s), r1(lb.getOrDefault(s, 0.0)),
                    r1(la.getOrDefault(s, 0.0))));
        }
        double peakBefore = lb.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double peakAfter = la.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        return new ScenarioResponse.RouteResult(c.route(), c.kind(), c.baseRoute(), r1(c.before()), r1(c.after()),
                c.splitAt(), c.fromSeq(), c.toSeq(), r1(peakBefore), r1(peakAfter), loads);
    }

    private List<ScenarioResponse.RoadSegment> road(ScenarioEngine.Result result, Set<String> routes,
                                                    List<ScenarioEngine.Operation> ops,
                                                    Map<String, RoadLoadQueryRepository.StopPoint> points,
                                                    Map<String, ScenarioQueryRepository.PairSegment> pairSegments) {
        List<RouteLoadCalculator.Station> stations = pathRepository.stations(routes);
        List<RouteLoadCalculator.Leg> beforeLegs = new ArrayList<>();
        result.before().values().forEach(list -> list.forEach(l -> beforeLegs.add(roadLeg(l, l.route()))));
        List<RouteLoadCalculator.Leg> afterLegs = new ArrayList<>();
        Map<String, double[]> drawnLoads = new LinkedHashMap<>();
        for (ScenarioEngine.Placed p : result.afterPlaced()) {
            if (p.roadRoute() != null) {
                afterLegs.add(roadLeg(p.leg(), p.roadRoute()));
            } else {
                List<String> stops = result.drawnStops().get(p.leg().route());
                for (int i = p.drawnIndexFrom(); i < p.drawnIndexTo(); i++) {
                    drawnLoads.computeIfAbsent(stops.get(i) + ">" + stops.get(i + 1), k -> new double[1])[0]
                            += p.leg().passengers();
                }
            }
        }
        RouteLoadCalculator.Result before = RouteLoadCalculator.compute(beforeLegs, stations);
        RouteLoadCalculator.Result after = RouteLoadCalculator.compute(afterLegs, stations);
        Set<String> variants = new HashSet<>();
        before.loads().keySet().forEach(r -> variants.add(r.variant()));
        after.loads().keySet().forEach(r -> variants.add(r.variant()));
        List<RouteLoadQueryRepository.SegmentRow> rows = pathRepository.segments(variants);

        record Acc(String from, String to, List<double[]> path, double[] load, boolean straight, Set<String> routes) {
        }
        Map<String, Acc> merged = new LinkedHashMap<>();
        for (int side = 0; side < 2; side++) {
            for (DongRouteLoadResponse.Segment s : DongRouteLoadService.merge(side == 0 ? before : after, rows)) {
                String key = s.fromArs() + ">" + s.toArs();
                Acc acc = merged.computeIfAbsent(key, k -> new Acc(s.fromArs(), s.toArs(), s.path(),
                        new double[2], false, new HashSet<>()));
                acc.load()[side] += s.load();
                acc.routes().addAll(s.routes());
            }
        }
        Set<String> named = new HashSet<>();
        for (ScenarioEngine.Operation op : ops) {
            if (op.route() != null) {
                named.add(op.route());
            }
        }
        drawnLoads.forEach((key, load) -> {
            String[] ends = key.split(">");
            ScenarioQueryRepository.PairSegment seg = pairSegments.get(key);
            List<double[]> path = seg != null && seg.path().size() >= 2 ? seg.path() : straight(points, ends[0], ends[1]);
            Acc acc = merged.computeIfAbsent(key, k -> new Acc(ends[0], ends[1], path, new double[2],
                    seg == null, new HashSet<>()));
            acc.load()[1] += load[0];
            acc.routes().add("*drawn");
        });

        List<ScenarioResponse.RoadSegment> out = new ArrayList<>();
        for (Acc a : merged.values()) {
            boolean ofScenario = a.routes().contains("*drawn") || a.routes().stream().anyMatch(named::contains);
            boolean changed = Math.abs(a.load()[1] - a.load()[0]) >= ROAD_CHANGE_MIN;
            if ((ofScenario || changed) && a.path() != null && a.path().size() >= 2) {
                out.add(new ScenarioResponse.RoadSegment(a.from(), a.to(), r1(a.load()[0]), r1(a.load()[1]),
                        a.path(), a.straight()));
            }
        }
        return out;
    }

    private static RouteLoadCalculator.Leg roadLeg(Leg l, String route) {
        return new RouteLoadCalculator.Leg(route, l.boardArs(), l.alightArs(), l.passengers(),
                l.boardSeq(), l.alightSeq());
    }

    private static List<double[]> straight(Map<String, RoadLoadQueryRepository.StopPoint> points, String a, String b) {
        RoadLoadQueryRepository.StopPoint p = points.get(a);
        RoadLoadQueryRepository.StopPoint q = points.get(b);
        if (p == null || q == null) {
            return List.of();
        }
        return List.of(new double[] {p.lat(), p.lng()}, new double[] {q.lat(), q.lng()});
    }

    private static ScenarioResponse.NewRoute newRoute(ScenarioEngine.Operation op,
                                                      Map<String, RoadLoadQueryRepository.StopPoint> points,
                                                      Map<String, ScenarioQueryRepository.PairSegment> pairSegments,
                                                      double speed) {
        List<String> stops = op.stops();
        double roadM = 0;
        double straightM = 0;
        for (int i = 0; i + 1 < stops.size(); i++) {
            ScenarioQueryRepository.PairSegment seg = pairSegments.get(stops.get(i) + ">" + stops.get(i + 1));
            if (seg != null) {
                roadM += seg.lengthM();
            } else {
                RoadLoadQueryRepository.StopPoint p = points.get(stops.get(i));
                RoadLoadQueryRepository.StopPoint q = points.get(stops.get(i + 1));
                if (p != null && q != null) {
                    double d = haversineM(p.lat(), p.lng(), q.lat(), q.lng());
                    straightM += d;
                    roadM += d * DETOUR;
                }
            }
        }
        double km = roadM / 1000;
        // The stops are one direction; the bus comes back the same way.
        double roundTripMin = 2 * km / speed * 60;
        Integer buses = op.headwayMin() == null ? null : (int) Math.ceil(roundTripMin / op.headwayMin());
        List<ScenarioResponse.Stop> stopList = new ArrayList<>();
        for (String s : stops) {
            RoadLoadQueryRepository.StopPoint p = points.get(s);
            stopList.add(new ScenarioResponse.Stop(s, p == null ? null : p.name(),
                    p == null ? null : p.lat(), p == null ? null : p.lng()));
        }
        return new ScenarioResponse.NewRoute(op.name(), stopList, r1(km), r1(straightM / 1000),
                r1(roundTripMin), op.headwayMin(), buses);
    }

    static double haversineM(double lat1, double lng1, double lat2, double lng2) {
        double r = 6_371_000;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.asin(Math.sqrt(h));
    }

    static double r1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
