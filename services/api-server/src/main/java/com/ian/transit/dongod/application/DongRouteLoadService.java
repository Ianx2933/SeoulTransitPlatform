package com.ian.transit.dongod.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import com.ian.transit.period.OdBasis;
import com.ian.transit.period.application.PeriodOdService;
import com.ian.transit.period.infrastructure.PeriodOdRepository.PeriodLeg;
import com.ian.transit.roadload.application.RoadLoadService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Bus riders of an area drawn on the road: route OD legs touching the area
 * placed on stop-to-stop segments (RouteLoadCalculator), then summed over
 * routes that share the same two stops, so a corridor shows its total.
 *
 * A period takes the legs from BigQuery (the area's stops are still found in
 * Cloud SQL); boarding hours cut the legs and add legendMax.
 */
@Service
@RequiredArgsConstructor
public class DongRouteLoadService {

    public static final int MAX_UNPLACED_ROUTES = 10;

    private final RouteLoadQueryRepository repository;
    /** Only used for a period or hours; null in tests of the plain typical day. */
    private final PeriodOdService periodService;

    public DongRouteLoadResponse load(String date, String levelName, String code, String direction) {
        return load(OdBasis.of(date, null, null, null), levelName, code, direction);
    }

    /** od = boarding in the area, do = alighting in it, both = either, a trip inside it once. */
    private List<RouteLoadCalculator.Leg> dayLegs(OdBasis basis, DongArea level, String code, String dir) {
        if (basis.typical()) {
            return repository.legs(basis.label(), level, code, dir);
        }
        List<String> keys = repository.areaStopKeys(level, code);
        Map<String, RouteLoadCalculator.Leg> byPair = new LinkedHashMap<>();
        if (!dir.equals("do")) {
            periodService.legsByStops(basis, keys, false).forEach(l -> byPair.putIfAbsent(pairKey(l), roadLeg(l)));
        }
        if (!dir.equals("od")) {
            periodService.legsByStops(basis, keys, true).forEach(l -> byPair.putIfAbsent(pairKey(l), roadLeg(l)));
        }
        return new ArrayList<>(byPair.values());
    }

    private static String pairKey(PeriodLeg l) {
        return l.route() + "|" + l.boardArs() + "|" + l.alightArs();
    }

    private static RouteLoadCalculator.Leg roadLeg(PeriodLeg l) {
        return new RouteLoadCalculator.Leg(l.route(), l.boardArs(), l.alightArs(), l.passengers(),
                l.boardSeq(), l.alightSeq());
    }

    public DongRouteLoadResponse load(OdBasis basis, String levelName, String code, String direction) {
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        level.requireCode(code);
        String dir = direction == null || direction.isBlank() ? "both" : direction.trim().toLowerCase();
        if (!dir.equals("od") && !dir.equals("do") && !dir.equals("both")) {
            throw new IllegalArgumentException("direction must be od, do or both, got " + direction);
        }
        List<RouteLoadCalculator.Leg> day = dayLegs(basis, level, code, dir);
        List<RouteLoadCalculator.Leg> legs = RoadLoadService.split(periodService, basis, day);
        if (basis.hours() != null && legs.isEmpty()) {
            throw new NotFoundException("No bus riders for " + code + " in " + basis);
        }
        Set<String> routes = day.stream().map(RouteLoadCalculator.Leg::route).collect(Collectors.toSet());
        List<RouteLoadCalculator.Station> stations = repository.stations(routes);
        RouteLoadCalculator.Result result = RouteLoadCalculator.compute(legs, stations);
        RouteLoadCalculator.Result whole = legs == day ? result : RouteLoadCalculator.compute(day, stations);

        Set<String> variants = whole.loads().keySet().stream()
                .map(RouteLoadCalculator.SegmentRef::variant).collect(Collectors.toSet());
        List<RouteLoadQueryRepository.SegmentRow> rows = repository.segments(variants);
        List<DongRouteLoadResponse.Segment> segments = merge(result, rows);
        Double legendMax = RoadLoadService.legendMax(periodService, basis, day, stations, null, rows);

        List<DongRouteLoadResponse.Unplaced> unplaced = result.unplacedByRoute().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(MAX_UNPLACED_ROUTES)
                .map(e -> new DongRouteLoadResponse.Unplaced(e.getKey(), e.getValue()))
                .toList();
        return new DongRouteLoadResponse(level.name().toLowerCase(), code, basis.dayType(), dir,
                result.placed() + result.unplaced(), result.placed(), result.unplaced(), segments, unplaced, legendMax);
    }

    /**
     * Segments with riders, summed by (from ARS, to ARS); a segment whose ends
     * lack an ARS stays on its own. The first geometry seen draws the pair.
     */
    public static List<DongRouteLoadResponse.Segment> merge(RouteLoadCalculator.Result result,
                                                     List<RouteLoadQueryRepository.SegmentRow> rows) {
        Map<RouteLoadCalculator.SegmentRef, RouteLoadQueryRepository.SegmentRow> byRef = new HashMap<>();
        for (RouteLoadQueryRepository.SegmentRow r : rows) {
            byRef.put(new RouteLoadCalculator.SegmentRef(r.variant(), r.seq()), r);
        }
        record Acc(String from, String to, List<double[]> path, double[] load, Map<String, Double> routes,
                   String fromName, String toName) {
        }
        Map<String, Acc> merged = new LinkedHashMap<>();
        result.loads().forEach((ref, load) -> {
            RouteLoadQueryRepository.SegmentRow row = byRef.get(ref);
            if (row == null || row.path().size() < 2) {
                return;
            }
            String from = RouteLoadCalculator.pad(row.fromArs());
            String to = RouteLoadCalculator.pad(row.toArs());
            String key = from != null && to != null ? from + ">" + to : ref.variant() + "#" + ref.seq();
            Acc acc = merged.computeIfAbsent(key, k -> new Acc(from, to, row.path(), new double[1], new TreeMap<>(),
                    row.fromName(), row.toName()));
            acc.load()[0] += load;
            acc.routes().merge(routeName(ref.variant()), load, Double::sum);
        });
        List<DongRouteLoadResponse.Segment> out = new ArrayList<>();
        for (Acc a : merged.values()) {
            out.add(new DongRouteLoadResponse.Segment(a.from(), a.to(), a.load()[0],
                    List.copyOf(a.routes().keySet()), a.routes(), a.path(), a.fromName(), a.toName()));
        }
        out.sort(Comparator.comparing(DongRouteLoadResponse.Segment::load).reversed());
        return out;
    }

    /** TOPIS route name of a stored variant ("2312|2312A" -> "2312"). */
    public static String routeName(String variant) {
        int bar = variant.indexOf('|');
        return bar < 0 ? variant : variant.substring(0, bar);
    }
}
