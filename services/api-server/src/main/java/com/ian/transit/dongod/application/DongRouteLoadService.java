package com.ian.transit.dongod.application;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;
import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
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
 */
@Service
@RequiredArgsConstructor
public class DongRouteLoadService {

    public static final int MAX_UNPLACED_ROUTES = 10;

    private final RouteLoadQueryRepository repository;

    public DongRouteLoadResponse load(String date, String levelName, String code, String direction) {
        DongArea level = DongArea.parse(levelName, DongArea.DONG);
        level.requireCode(code);
        String dir = direction == null || direction.isBlank() ? "both" : direction.trim().toLowerCase();
        if (!dir.equals("od") && !dir.equals("do") && !dir.equals("both")) {
            throw new IllegalArgumentException("direction must be od, do or both, got " + direction);
        }
        String label = TypicalDay.label(date);
        List<RouteLoadCalculator.Leg> legs = repository.legs(label, level, code, dir);
        Set<String> routes = legs.stream().map(RouteLoadCalculator.Leg::route).collect(Collectors.toSet());
        RouteLoadCalculator.Result result = RouteLoadCalculator.compute(legs, repository.stations(routes));

        Set<String> variants = result.loads().keySet().stream()
                .map(RouteLoadCalculator.SegmentRef::variant).collect(Collectors.toSet());
        List<DongRouteLoadResponse.Segment> segments = merge(result, repository.segments(variants));

        List<DongRouteLoadResponse.Unplaced> unplaced = result.unplacedByRoute().entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(MAX_UNPLACED_ROUTES)
                .map(e -> new DongRouteLoadResponse.Unplaced(e.getKey(), e.getValue()))
                .toList();
        String dayType = label.startsWith("typical_") ? label.substring("typical_".length()) : label;
        return new DongRouteLoadResponse(level.name().toLowerCase(), code, dayType, dir,
                result.placed() + result.unplaced(), result.placed(), result.unplaced(), segments, unplaced);
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
