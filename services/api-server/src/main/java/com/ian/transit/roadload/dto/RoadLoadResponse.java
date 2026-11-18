package com.ian.transit.roadload.dto;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.dongod.dto.DongRouteLoadResponse;

import java.util.List;

/**
 * Riders on the road for chosen routes, one stop or one road segment.
 *
 * kind is routes, stop or segment. For a stop or a segment, origins and
 * destinations are the stops those riders boarded and alighted at (busiest
 * first, capped), so the map can show where they come from and go to.
 * segments has the same shape as the district road view.
 */
public record RoadLoadResponse(
    String kind,
    String title,
    String dayType,
    Double passengers,
    Double placedPassengers,
    Double unplacedPassengers,
    List<String> routes,
    List<DongRouteLoadResponse.Segment> segments,
    List<Point> origins,
    List<Point> destinations,
    List<DongRouteLoadResponse.Unplaced> unplacedRoutes
) {
    public RoadLoadResponse {
        passengers = TypicalDay.round(passengers);
        placedPassengers = TypicalDay.round(placedPassengers);
        unplacedPassengers = TypicalDay.round(unplacedPassengers);
    }

    public record Point(String ars, String name, double lat, double lng, Double riders) {
        public Point {
            riders = TypicalDay.round(riders);
        }
    }
}
