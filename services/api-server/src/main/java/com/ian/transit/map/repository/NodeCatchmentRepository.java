package com.ian.transit.map.repository;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.dto.NodeCatchmentDemandResponse;
import com.ian.transit.map.dto.NodeDemandResponse;
import com.ian.transit.map.dto.NodeRouteDemandResponse;
import com.ian.transit.map.repository.support.DistanceCalculator;
import com.ian.transit.map.repository.support.NodeIdNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Repository for coordinate/radius-based node catchment demand.
 *
 * The MVP implementation calculates distance in Java. It can later move to
 * PostGIS ST_DWithin without changing the controller contract.
 */
@Repository
public class NodeCatchmentRepository {

    private final MapDemandRepository mapDemandRepository;

    public NodeCatchmentRepository(MapDemandRepository mapDemandRepository) {
        this.mapDemandRepository = mapDemandRepository;
    }

    /**
     * Loads all-route demand for a coordinate-based node catchment.
     */
    public NodeCatchmentDemandResponse findNodeCatchmentDemand(
            double centerLat,
            double centerLng,
            int radiusMeters,
            List<String> modes,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours
    ) {
        List<MapDemandResponse> allRows = new ArrayList<>();

        for (String mode : modes) {
            allRows.addAll(mapDemandRepository.findMapDemand(
                    mode,
                    dayTypes,
                    dayAggregation,
                    hours,
                    List.of()
            ));
        }

        List<MapDemandResponse> catchmentRows = allRows.stream()
                .filter(row -> DistanceCalculator.calculateDistanceMeters(
                        centerLat,
                        centerLng,
                        row.lat(),
                        row.lng()
                ) <= radiusMeters)
                .toList();

        List<NodeDemandResponse> nodes = groupRowsByNode(
                catchmentRows,
                centerLat,
                centerLng
        );

        List<NodeRouteDemandResponse> routes = groupRowsByRoute(catchmentRows);

        long boarding = routes.stream().mapToLong(NodeRouteDemandResponse::boarding).sum();
        long alighting = routes.stream().mapToLong(NodeRouteDemandResponse::alighting).sum();

        return new NodeCatchmentDemandResponse(
                centerLat,
                centerLng,
                radiusMeters,
                boarding,
                alighting,
                boarding + alighting,
                nodes,
                routes
        );
    }

    /**
     * Groups catchment rows by physical node.
     */
    private List<NodeDemandResponse> groupRowsByNode(
            List<MapDemandResponse> rows,
            double centerLat,
            double centerLng
    ) {
        Map<String, MutableNodeDemand> grouped = new LinkedHashMap<>();

        for (MapDemandResponse row : rows) {
            String key = row.mode() + "|" + NodeIdNormalizer.normalize(row.nodeId());

            MutableNodeDemand value = grouped.computeIfAbsent(
                    key,
                    ignored -> new MutableNodeDemand(
                            row.mode(),
                            row.nodeId(),
                            row.nodeName(),
                            row.lat(),
                            row.lng(),
                            DistanceCalculator.calculateDistanceMeters(
                                    centerLat,
                                    centerLng,
                                    row.lat(),
                                    row.lng()
                            )
                    )
            );

            value.boarding += row.boarding();
            value.alighting += row.alighting();
        }

        return grouped.values().stream()
                .map(MutableNodeDemand::toResponse)
                .sorted(Comparator.comparingDouble(NodeDemandResponse::distanceMeters))
                .toList();
    }

    /**
     * Groups catchment rows by route.
     */
    private List<NodeRouteDemandResponse> groupRowsByRoute(List<MapDemandResponse> rows) {
        Map<String, MutableRouteDemand> grouped = new LinkedHashMap<>();

        for (MapDemandResponse row : rows) {
            String key = row.mode() + "|" + row.serviceId();

            MutableRouteDemand value = grouped.computeIfAbsent(
                    key,
                    ignored -> new MutableRouteDemand(row.mode(), row.serviceId())
            );

            value.boarding += row.boarding();
            value.alighting += row.alighting();
        }

        return grouped.values().stream()
                .map(MutableRouteDemand::toResponse)
                .sorted(Comparator.comparingLong(NodeRouteDemandResponse::total).reversed())
                .toList();
    }

    private static class MutableNodeDemand {
        private final String mode;
        private final String nodeId;
        private final String nodeName;
        private final double lat;
        private final double lng;
        private final double distanceMeters;
        private long boarding;
        private long alighting;

        private MutableNodeDemand(
                String mode,
                String nodeId,
                String nodeName,
                double lat,
                double lng,
                double distanceMeters
        ) {
            this.mode = mode;
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.lat = lat;
            this.lng = lng;
            this.distanceMeters = distanceMeters;
        }

        private NodeDemandResponse toResponse() {
            return new NodeDemandResponse(
                    mode,
                    nodeId,
                    nodeName,
                    lat,
                    lng,
                    distanceMeters,
                    boarding,
                    alighting,
                    boarding + alighting
            );
        }
    }

    private static class MutableRouteDemand {
        private final String mode;
        private final String serviceId;
        private long boarding;
        private long alighting;

        private MutableRouteDemand(String mode, String serviceId) {
            this.mode = mode;
            this.serviceId = serviceId;
        }

        private NodeRouteDemandResponse toResponse() {
            return new NodeRouteDemandResponse(
                    mode,
                    serviceId,
                    boarding,
                    alighting,
                    boarding + alighting
            );
        }
    }
}
