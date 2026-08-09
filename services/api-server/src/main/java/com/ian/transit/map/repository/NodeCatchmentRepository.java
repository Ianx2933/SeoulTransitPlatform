package com.ian.transit.map.repository;

import com.ian.transit.map.dto.NodeCatchmentDemandResponse;
import com.ian.transit.map.dto.NodeDemandResponse;
import com.ian.transit.map.dto.NodeRouteDemandResponse;
import com.ian.transit.map.repository.support.DemandSqlSupport;
import com.ian.transit.map.repository.support.NodeIdNormalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for coordinate/radius-based node catchment demand.
 *
 * The radius filter is pushed down to PostGIS (ST_DWithin on geography) and
 * per-node distance comes back from ST_Distance. The previous MVP loaded the
 * ENTIRE city's demand rows for each request and measured distance in Java,
 * which made every map click a full-table scan plus a large JDBC transfer.
 *
 * Grouping into node/route breakdowns stays in Java: the row count after the
 * radius filter is small, and keeping the grouping identical preserves the
 * exact response contract of the previous implementation.
 */
@Repository
public class NodeCatchmentRepository {

    private final JdbcTemplate jdbcTemplate;

    public NodeCatchmentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
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
        List<CatchmentRow> catchmentRows = new ArrayList<>();

        if (modes.contains("subway")) {
            catchmentRows.addAll(findSubwayCatchmentRows(
                    centerLat, centerLng, radiusMeters, dayTypes, dayAggregation, hours
            ));
        }

        if (modes.contains("bus")) {
            catchmentRows.addAll(findBusCatchmentRows(
                    centerLat, centerLng, radiusMeters, dayTypes, dayAggregation, hours
            ));
        }

        List<NodeDemandResponse> nodes = groupRowsByNode(catchmentRows);
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
     * Loads subway route-node rows inside the radius.
     *
     * Parameter order follows SQL text order exactly:
     * (1,2) center lng/lat for the SELECT distance expression,
     * (3,4,5) center lng/lat + radius for ST_DWithin,
     * then day-type and hour IN-clause values.
     */
    private List<CatchmentRow> findSubwayCatchmentRows(
            double centerLat,
            double centerLng,
            int radiusMeters,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours
    ) {
        int divisor = DemandSqlSupport.getDayAggregationDivisor(dayTypes, dayAggregation);
        String boardingExpression = DemandSqlSupport.buildDemandAggregationExpression("d.boarding", divisor);
        String alightingExpression = DemandSqlSupport.buildDemandAggregationExpression("d.alighting", divisor);

        StringBuilder sql = new StringBuilder("""
                SELECT
                    d.mode,
                    d.service_id,
                    d.node_id,
                    d.node_name,
                    s.lat,
                    s.lng,
                    ST_Distance(
                        ST_SetSRID(ST_MakePoint(s.lng, s.lat), 4326)::geography,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                    ) AS distance_meters,
                """);

        sql.append("    ").append(boardingExpression).append(" AS boarding,\n");
        sql.append("    ").append(alightingExpression).append(" AS alighting\n");

        sql.append("""
                FROM integrated_hourly_transit_demand_light d
                JOIN subway_station_location s
                  ON d.service_id = s.line_name
                 AND regexp_replace(d.node_name, '\\([^)]*\\)', '', 'g')
                   = regexp_replace(s.station_name, '\\([^)]*\\)', '', 'g')
                WHERE d.mode = 'subway'
                  AND ST_DWithin(
                      ST_SetSRID(ST_MakePoint(s.lng, s.lat), 4326)::geography,
                      ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                      ?
                  )
                """);

        List<Object> params = new ArrayList<>();
        params.add(centerLng);
        params.add(centerLat);
        params.add(centerLng);
        params.add(centerLat);
        params.add(radiusMeters);

        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);

        sql.append("""
                GROUP BY
                    d.mode,
                    d.service_id,
                    d.node_id,
                    d.node_name,
                    s.lat,
                    s.lng
                """);

        return jdbcTemplate.query(sql.toString(), this::mapCatchmentRow, params.toArray());
    }

    /**
     * Loads bus route-node rows inside the radius from both coordinate
     * sources, mirroring the union structure of MapDemandRepository.
     *
     * Parameter order follows SQL text order exactly:
     * (1,2) center lng/lat for the outer SELECT distance expression,
     * (3,4,5) primary-branch ST_DWithin center lng/lat + radius,
     * primary-branch day/hour values,
     * (…)  integrated-branch ST_DWithin center lng/lat + radius,
     * integrated-branch day/hour values.
     */
    private List<CatchmentRow> findBusCatchmentRows(
            double centerLat,
            double centerLng,
            int radiusMeters,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours
    ) {
        int divisor = DemandSqlSupport.getDayAggregationDivisor(dayTypes, dayAggregation);
        String boardingExpression = DemandSqlSupport.buildDemandAggregationExpression("x.boarding", divisor);
        String alightingExpression = DemandSqlSupport.buildDemandAggregationExpression("x.alighting", divisor);

        StringBuilder sql = new StringBuilder("""
                SELECT
                    x.mode,
                    x.service_id,
                    x.node_id,
                    x.node_name,
                    x.lat,
                    x.lng,
                    ST_Distance(
                        ST_SetSRID(ST_MakePoint(x.lng, x.lat), 4326)::geography,
                        ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography
                    ) AS distance_meters,
                """);

        sql.append("    ").append(boardingExpression).append(" AS boarding,\n");
        sql.append("    ").append(alightingExpression).append(" AS alighting\n");

        sql.append("""
                FROM (
                    SELECT
                        d.mode,
                        d.service_id,
                        d.node_id,
                        b."정류장명" AS node_name,
                        b."위도" AS lat,
                        b."경도" AS lng,
                        CAST(d.boarding AS BIGINT) AS boarding,
                        CAST(d.alighting AS BIGINT) AS alighting
                    FROM integrated_hourly_transit_demand_light d
                    JOIN bus_stop_location b
                      ON LPAD(d.node_id::text, 5, '0')
                       = LPAD(b."정류장번호"::text, 5, '0')
                    WHERE d.mode = 'bus'
                      AND LPAD(d.node_id::text, 5, '0') <> '00000'
                      AND LPAD(b."정류장번호"::text, 5, '0') <> '00000'
                      AND ST_DWithin(
                          COALESCE(
                              b.geom,
                              ST_SetSRID(ST_MakePoint(b."경도", b."위도"), 4326)
                          )::geography,
                          ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                          ?
                      )
                """);

        List<Object> params = new ArrayList<>();
        params.add(centerLng);
        params.add(centerLat);
        params.add(centerLng);
        params.add(centerLat);
        params.add(radiusMeters);

        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);

        sql.append("""
                    UNION ALL

                    SELECT
                        d.mode,
                        d.service_id,
                        d.node_id,
                        b.stop_name AS node_name,
                        b.lat,
                        b.lng,
                        CAST(d.boarding AS BIGINT) AS boarding,
                        CAST(d.alighting AS BIGINT) AS alighting
                    FROM integrated_hourly_transit_demand_light d
                    JOIN bus_stop_demand_node_mapping m
                      ON d.service_id = m.service_id
                     AND LPAD(d.node_id::text, 5, '0')
                       = LPAD(m.demand_node_id::text, 5, '0')
                    JOIN integrated_bus_stop_location b
                      ON m.canonical_node_id = b.canonical_node_id
                    WHERE d.mode = 'bus'
                      AND LPAD(d.node_id::text, 5, '0') <> '00000'
                      AND LPAD(m.demand_node_id::text, 5, '0') <> '00000'
                      AND ST_DWithin(
                          COALESCE(
                              b.geom,
                              ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)
                          )::geography,
                          ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                          ?
                      )
                """);

        params.add(centerLng);
        params.add(centerLat);
        params.add(radiusMeters);

        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);

        sql.append("""
                      AND NOT EXISTS (
                          SELECT 1
                          FROM bus_stop_location existing
                          WHERE LPAD(d.node_id::text, 5, '0')
                              = LPAD(existing."정류장번호"::text, 5, '0')
                      )
                ) x
                GROUP BY
                    x.mode,
                    x.service_id,
                    x.node_id,
                    x.node_name,
                    x.lat,
                    x.lng
                """);

        return jdbcTemplate.query(sql.toString(), this::mapCatchmentRow, params.toArray());
    }

    private CatchmentRow mapCatchmentRow(ResultSet rs, int rowNum) throws SQLException {
        return new CatchmentRow(
                rs.getString("mode"),
                rs.getString("service_id"),
                rs.getString("node_id"),
                rs.getString("node_name"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getDouble("distance_meters"),
                rs.getLong("boarding"),
                rs.getLong("alighting")
        );
    }

    /**
     * Groups catchment rows by physical node.
     */
    private List<NodeDemandResponse> groupRowsByNode(List<CatchmentRow> rows) {
        Map<String, MutableNodeDemand> grouped = new LinkedHashMap<>();

        for (CatchmentRow row : rows) {
            String key = row.mode() + "|" + NodeIdNormalizer.normalize(row.nodeId());

            MutableNodeDemand value = grouped.computeIfAbsent(
                    key,
                    ignored -> new MutableNodeDemand(
                            row.mode(),
                            row.nodeId(),
                            row.nodeName(),
                            row.lat(),
                            row.lng(),
                            row.distanceMeters()
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
    private List<NodeRouteDemandResponse> groupRowsByRoute(List<CatchmentRow> rows) {
        Map<String, MutableRouteDemand> grouped = new LinkedHashMap<>();

        for (CatchmentRow row : rows) {
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

    /**
     * One route-node row inside the radius, with SQL-computed distance.
     */
    private record CatchmentRow(
            String mode,
            String serviceId,
            String nodeId,
            String nodeName,
            double lat,
            double lng,
            double distanceMeters,
            long boarding,
            long alighting
    ) {
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
