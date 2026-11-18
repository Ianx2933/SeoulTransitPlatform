package com.ian.transit.curatedod.application;

import com.ian.transit.common.exception.NotFoundException;
import com.ian.transit.curatedod.dto.RoutePathResponse;
import com.ian.transit.curatedod.infrastructure.SeoulBusRouteClient;
import com.ian.transit.curatedod.infrastructure.StoredRoutePathRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Road path, turnaround and stops of a bus route. The stored paths
 * (bus_route_path_*, filled weekly-ish by pipelines/bus_route_path) come first:
 * they know routes the API spells differently (한강버스01, 2312, 8762) and cost
 * no API calls. Routes not stored fall back to the city's API (three calls).
 * Answers are kept in memory for a day; a miss is remembered for an hour.
 */
@Service
@RequiredArgsConstructor
public class RoutePathService {

    static final Duration TTL = Duration.ofHours(24);
    static final Duration MISS_TTL = Duration.ofHours(1);

    private final SeoulBusRouteClient client;
    private final StoredRoutePathRepository stored;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(RoutePathResponse value, Instant expires) {
    }

    public RoutePathResponse path(String routeName) {
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
        RoutePathResponse fromStore = fromStored(name, stored.stations(name));
        if (fromStore != null) {
            return fromStore;
        }
        if (!client.configured()) {
            return null;
        }
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

    private RoutePathResponse fromStored(String name, List<StoredRoutePathRepository.StationRow> stations) {
        if (stations.isEmpty()) {
            return null;
        }
        String variant = longestVariant(stations);
        List<double[]> path = joinLines(stored.segmentLines(variant));
        if (path.size() < 2) {
            return null;
        }
        List<StoredRoutePathRepository.StationRow> own = stations.stream()
                .filter(s -> s.variant().equals(variant)).toList();
        StoredRoutePathRepository.StationRow turn = own.stream()
                .filter(StoredRoutePathRepository.StationRow::turnaround).findFirst().orElse(null);
        return new RoutePathResponse(name, own.get(0).routeId(), path,
                turn == null ? null : turn.ars(), turn == null ? null : turn.name(), stopsOf(stations), "stored");
    }

    /** The stored variant with the most stations (the full run of a route kept as several). */
    static String longestVariant(List<StoredRoutePathRepository.StationRow> stations) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (StoredRoutePathRepository.StationRow s : stations) {
            counts.merge(s.variant(), 1L, Long::sum);
        }
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
    }

    /** Segment lines joined into one, dropping the point each shares with the one before. */
    static List<double[]> joinLines(List<List<double[]>> lines) {
        List<double[]> out = new ArrayList<>();
        for (List<double[]> line : lines) {
            for (double[] p : line) {
                double[] last = out.isEmpty() ? null : out.get(out.size() - 1);
                if (last == null || last[0] != p[0] || last[1] != p[1]) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    /** Every variant's stops, one per ARS number (first seen). */
    static List<RoutePathResponse.Stop> stopsOf(List<StoredRoutePathRepository.StationRow> stations) {
        Map<String, RoutePathResponse.Stop> byArs = new LinkedHashMap<>();
        for (StoredRoutePathRepository.StationRow s : stations) {
            if (s.ars() != null && !byArs.containsKey(s.ars())) {
                byArs.put(s.ars(), new RoutePathResponse.Stop(s.ars(), s.name(), s.lat(), s.lng()));
            }
        }
        return List.copyOf(byArs.values());
    }
}
