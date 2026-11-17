package com.ian.transit.dongod.application;

import com.ian.transit.dongod.dto.DongRoutesResponse.Destination;
import com.ian.transit.dongod.dto.DongRoutesResponse.Route;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups (route, alighting district, passengers) rows into routes sorted by
 * riders, each with its top destination districts. Pure, for tests.
 */
public final class DongRouteFlows {

    private DongRouteFlows() {
    }

    /** One SQL row: riders of a route boarding in the district, by alighting district. */
    public record Row(String route, String destCode, String destName, double passengers) {
    }

    public record Result(List<Route> routes, double boardings, double unplaced) {
    }

    public static Result group(List<Row> rows, int destinationsPerRoute) {
        Map<String, Map<String, Destination>> byRoute = new LinkedHashMap<>();
        Map<String, Double> totals = new LinkedHashMap<>();
        double boardings = 0;
        double unplaced = 0;
        for (Row r : rows) {
            boardings += r.passengers();
            totals.merge(r.route(), r.passengers(), Double::sum);
            if (r.destCode() == null) {
                unplaced += r.passengers();
                continue;
            }
            byRoute.computeIfAbsent(r.route(), k -> new LinkedHashMap<>())
                    .merge(r.destCode(), new Destination(r.destCode(), r.destName(), r.passengers()),
                            (a, b) -> new Destination(a.code(), a.name(), a.passengers() + b.passengers()));
        }
        List<Route> routes = new ArrayList<>();
        totals.forEach((route, total) -> {
            List<Destination> dests = new ArrayList<>(byRoute.getOrDefault(route, Map.of()).values());
            dests.sort(Comparator.comparingDouble(Destination::passengers).reversed()
                    .thenComparing(Destination::code));
            routes.add(new Route(route, total, dests.stream().limit(destinationsPerRoute).toList()));
        });
        routes.sort(Comparator.comparingDouble(Route::boardings).reversed().thenComparing(Route::route));
        return new Result(routes, boardings, unplaced);
    }
}
