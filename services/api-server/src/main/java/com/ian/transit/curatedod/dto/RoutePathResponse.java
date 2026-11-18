package com.ian.transit.curatedod.dto;

import java.util.List;

/**
 * Road geometry of one bus route: path points in order as [lat, lng], the ARS
 * number and name of the stop the route turns at (transYn = Y, null when it
 * has none), and the route's stops with their positions.
 *
 * source is "stored" (bus_route_path_* tables, which also know routes the
 * API spells differently, such as 한강버스01) or "live" (the city's bus API).
 * stops lets the map place stops the demand layer does not have, such as the
 * Han River boat piers.
 */
public record RoutePathResponse(
    String 노선명,
    String routeId,
    List<double[]> path,
    String turnaroundArs,
    String turnaroundName,
    List<Stop> stops,
    String source
) {
    public RoutePathResponse(String 노선명, String routeId, List<double[]> path, String turnaroundArs,
                             String turnaroundName) {
        this(노선명, routeId, path, turnaroundArs, turnaroundName, List.of(), "live");
    }

    public record Stop(String ars, String name, double lat, double lng) {
    }
}
