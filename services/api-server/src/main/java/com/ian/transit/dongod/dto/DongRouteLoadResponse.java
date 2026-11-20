package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Riders of an area (행정동 or 구) on the road: every bus segment between two
 * stops carrying riders who board in the area (direction od), alight in it
 * (do) or either (both, a trip inside the area counted once), summed over all
 * routes that use the same pair of stops.
 *
 * byRoute keeps each route's riders on the segment, so the map can show
 * chosen routes only. path is [lat, lng] pairs along the road. fromName and
 * toName are the stop names (null when the stored path has none).
 * unplacedPassengers are riders on routes without a stored path (closed
 * routes) or whose stops the path lacks.
 *
 * legendMax, only with boarding hours: the busiest segment of the busiest
 * single hour of the day, so the map can keep one colour scale while the
 * hours change. null for the whole day.
 */
public record DongRouteLoadResponse(
    String level,
    String code,
    String dayType,
    String direction,
    Double passengers,
    Double placedPassengers,
    Double unplacedPassengers,
    List<Segment> segments,
    List<Unplaced> unplacedRoutes,
    Double legendMax
) {
    public DongRouteLoadResponse {
        passengers = TypicalDay.round(passengers);
        placedPassengers = TypicalDay.round(placedPassengers);
        unplacedPassengers = TypicalDay.round(unplacedPassengers);
        legendMax = TypicalDay.round(legendMax);
    }

    public record Segment(String fromArs, String toArs, Double load, List<String> routes,
                          Map<String, Double> byRoute, List<double[]> path, String fromName, String toName) {
        public Segment {
            load = TypicalDay.round(load);
            Map<String, Double> rounded = new TreeMap<>();
            byRoute.forEach((route, riders) -> rounded.put(route, TypicalDay.round(riders)));
            byRoute = rounded;
        }
    }

    public record Unplaced(String route, Double passengers) {
        public Unplaced {
            passengers = TypicalDay.round(passengers);
        }
    }
}
