package com.ian.transit.curatedod.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.curatedod.dto.RoutePathResponse;
import com.ian.transit.curatedod.infrastructure.SeoulBusRouteClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Road path and turnaround of a bus route, kept in memory for a day: routes
 * change rarely, and each lookup is three calls to the city's API (route ID,
 * path, stops). A route the API does not know is remembered for an hour.
 */
@Service
@RequiredArgsConstructor
public class RoutePathService {

    static final Duration TTL = Duration.ofHours(24);
    static final Duration MISS_TTL = Duration.ofHours(1);

    private final SeoulBusRouteClient client;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(RoutePathResponse value, Instant expires) {
    }

    public RoutePathResponse path(String routeName) {
        if (!client.configured()) {
            throw new NotFoundException("Route paths are not available (no bus API key)");
        }
        String name = routeName.trim();
        Cached hit = cache.get(name);
        if (hit != null && hit.expires().isAfter(Instant.now())) {
            if (hit.value() == null) {
                throw new NotFoundException("No route path for " + name);
            }
            return hit.value();
        }
        RoutePathResponse value = load(name);
        cache.put(name, new Cached(value, Instant.now().plus(value == null ? MISS_TTL : TTL)));
        if (value == null) {
            throw new NotFoundException("No route path for " + name);
        }
        return value;
    }

    private RoutePathResponse load(String name) {
        String routeId = client.routeList(name).stream()
                .filter(r -> name.equals(r.get("busRouteNm")))
                .map(r -> r.get("busRouteId"))
                .findFirst()
                .orElse(null);
        if (routeId == null) {
            return null;
        }
        List<double[]> path = toPath(client.routePath(routeId));
        if (path.size() < 2) {
            return null;
        }
        String turnArs = null;
        String turnName = null;
        for (Map<String, String> s : client.stations(routeId)) {
            if ("Y".equalsIgnoreCase(s.get("transYn"))) {
                turnArs = s.get("arsId");
                turnName = s.get("stationNm");
                break;
            }
        }
        return new RoutePathResponse(name, routeId, path, turnArs, turnName);
    }

    /** [lat, lng] points in the API's order; rows without numbers are skipped. */
    static List<double[]> toPath(List<Map<String, String>> rows) {
        List<double[]> out = new ArrayList<>(rows.size());
        for (Map<String, String> r : rows) {
            try {
                double lng = Double.parseDouble(r.get("gpsX"));
                double lat = Double.parseDouble(r.get("gpsY"));
                out.add(new double[] {lat, lng});
            } catch (NullPointerException | NumberFormatException e) {
                // skip
            }
        }
        return out;
    }
}
