package com.ian.transit.curatedod.dto;

import java.util.List;

/**
 * Road geometry of one bus route from the Seoul bus route API: path points in
 * order as [lat, lng], and the ARS number of the stop the route turns at
 * (transYn = Y), or null when the route has none.
 */
public record RoutePathResponse(
    String 노선명,
    String routeId,
    List<double[]> path,
    String turnaroundArs,
    String turnaroundName
) {
}
