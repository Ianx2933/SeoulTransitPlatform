package com.ian.transit.map.repository;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.dto.NodeDemandDetailResponse;
import com.ian.transit.map.dto.NodeRouteDemandResponse;
import com.ian.transit.map.exception.NodeNotFoundException;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Repository;

/**
 * Repository for node-centered all-route demand detail.
 */
@Repository
public class NodeDemandRepository {

    private final MapDemandRepository mapDemandRepository;

    public NodeDemandRepository(MapDemandRepository mapDemandRepository) {
        this.mapDemandRepository = mapDemandRepository;
    }

    /**
     * Loads all-route demand for one selected stop or station node.
     */
    public NodeDemandDetailResponse findNodeDemandDetail(
            String mode,
            String nodeId,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours
    ) {
        List<MapDemandResponse> routeRows = mapDemandRepository.findMapDemandByNode(
                mode,
                nodeId,
                dayTypes,
                dayAggregation,
                hours
        );

        if (routeRows.isEmpty()) {
            throw new NodeNotFoundException(
                    "no demand rows for mode=" + mode + ", nodeId=" + nodeId
            );
        }

        MapDemandResponse first = routeRows.get(0);

        List<NodeRouteDemandResponse> routes = routeRows.stream()
                .map(row -> new NodeRouteDemandResponse(
                        row.mode(),
                        row.serviceId(),
                        row.boarding(),
                        row.alighting(),
                        row.boarding() + row.alighting()
                ))
                .sorted(Comparator.comparingLong(NodeRouteDemandResponse::total).reversed())
                .toList();

        long boarding = routes.stream().mapToLong(NodeRouteDemandResponse::boarding).sum();
        long alighting = routes.stream().mapToLong(NodeRouteDemandResponse::alighting).sum();

        return new NodeDemandDetailResponse(
                first.mode(),
                first.nodeId(),
                first.nodeName(),
                first.lat(),
                first.lng(),
                boarding,
                alighting,
                boarding + alighting,
                routes
        );
    }
}
