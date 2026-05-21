package com.ian.transit.map.repository;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.dto.NodeCatchmentDemandResponse;
import com.ian.transit.map.dto.NodeDemandDetailResponse;
import com.ian.transit.map.dto.NodeDemandResponse;
import com.ian.transit.map.dto.NodeRouteDemandResponse;
import com.ian.transit.map.exception.NodeNotFoundException;
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
 * Repository for map demand queries.
 *
 * JdbcTemplate is used because these queries include PostgreSQL-specific SQL,
 * dynamic IN clauses, regular expressions, and Korean column names.
 */
@Repository
public class MapDemandRepository {

    private static final double EARTH_RADIUS_METERS = 6371000.0;

    private final JdbcTemplate jdbcTemplate;

    public MapDemandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads route-selected map demand points by mode.
     */
    public List<MapDemandResponse> findMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines
    ) {
        if ("subway".equals(mode)) {
            return findSubwayMapDemand(mode, dayTypes, dayAggregation, hours, lines, null);
        }

        return findBusMapDemand(mode, dayTypes, dayAggregation, hours, lines, null);
    }

    /**
     * Loads all-route demand for one selected stop or station node.
     *
     * The nodeId filter is pushed into SQL rather than applied in Java to avoid
     * scanning the full demand table for a single-node lookup.
     */
    public NodeDemandDetailResponse findNodeDemandDetail(
            String mode,
            String nodeId,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours
    ) {
        List<MapDemandResponse> routeRows = findMapDemandByNode(
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

    /**
     * Loads route-level demand rows restricted to a single node.
     *
     * Bus uses LPAD-normalized comparison so callers can pass either '7616' or '07616'.
     */
    private List<MapDemandResponse> findMapDemandByNode(
            String mode,
            String nodeId,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours
    ) {
        if ("subway".equals(mode)) {
            return findSubwayMapDemand(mode, dayTypes, dayAggregation, hours, List.of(), nodeId);
        }

        return findBusMapDemand(mode, dayTypes, dayAggregation, hours, List.of(), nodeId);
    }

    /**
     * Loads all-route demand for a coordinate-based node catchment.
     *
     * This MVP version calculates distance in Java. It can later move to
     * PostGIS ST_DWithin when deployment and data volume require it.
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
            allRows.addAll(findMapDemand(
                    mode,
                    dayTypes,
                    dayAggregation,
                    hours,
                    List.of()
            ));
        }

        List<MapDemandResponse> catchmentRows = allRows.stream()
                .filter(row -> calculateDistanceMeters(centerLat, centerLng, row.lat(), row.lng()) <= radiusMeters)
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
     * Loads subway demand points.
     *
     * When nodeId is non-null, restricts the result to that single station.
     */
    private List<MapDemandResponse> findSubwayMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines,
            String nodeId
    ) {
        int divisor = getDayAggregationDivisor(dayTypes, dayAggregation);
        String boardingExpression = buildDemandAggregationExpression("d.boarding", divisor);
        String alightingExpression = buildDemandAggregationExpression("d.alighting", divisor);

        StringBuilder sql = new StringBuilder("""
                SELECT
                    d.mode,
                    d.service_id,
                    d.node_id,
                    d.node_name,
                    s.lat,
                    s.lng,
                """);

        sql.append("    ").append(boardingExpression).append(" AS boarding,\n");
        sql.append("    ").append(alightingExpression).append(" AS alighting\n");

        sql.append("""
                FROM integrated_hourly_transit_demand_light d
                JOIN subway_station_location s
                  ON d.service_id = s.line_name
                 AND regexp_replace(d.node_name, '\\(.*\\)', '', 'g')
                   = regexp_replace(s.station_name, '\\(.*\\)', '', 'g')
                WHERE d.mode = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(mode);

        appendInClause(sql, params, "d.day_type", dayTypes);
        appendInClause(sql, params, "d.hour", hours);
        appendInClause(sql, params, "d.service_id", lines);

        if (nodeId != null && !nodeId.isBlank()) {
            sql.append("  AND d.node_id = ?\n");
            params.add(nodeId);
        }

        sql.append("""
                GROUP BY
                    d.mode,
                    d.service_id,
                    d.node_id,
                    d.node_name,
                    s.lat,
                    s.lng
                ORDER BY d.service_id, d.node_name
                """);

        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    /**
     * Loads bus demand points from both coordinate sources.
     *
     * When nodeId is non-null, restricts the result to that single stop using
     * LPAD-normalized comparison so '7616' and '07616' resolve to the same node.
     */
    private List<MapDemandResponse> findBusMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines,
            String nodeId
    ) {
        int divisor = getDayAggregationDivisor(dayTypes, dayAggregation);
        String boardingExpression = buildDemandAggregationExpression("x.boarding", divisor);
        String alightingExpression = buildDemandAggregationExpression("x.alighting", divisor);

        StringBuilder sql = new StringBuilder("""
                SELECT
                    x.mode,
                    x.service_id,
                    x.node_id,
                    x.node_name,
                    x.lat,
                    x.lng,
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
                    WHERE d.mode = ?
                """);

        List<Object> params = new ArrayList<>();

        params.add(mode);
        appendInClause(sql, params, "d.day_type", dayTypes);
        appendInClause(sql, params, "d.hour", hours);
        appendInClause(sql, params, "d.service_id", lines);

        if (nodeId != null && !nodeId.isBlank()) {
            sql.append("      AND LPAD(d.node_id::text, 5, '0') = LPAD(?::text, 5, '0')\n");
            params.add(nodeId);
        }

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
                    WHERE d.mode = ?
                """);

        params.add(mode);
        appendInClause(sql, params, "d.day_type", dayTypes);
        appendInClause(sql, params, "d.hour", hours);
        appendInClause(sql, params, "d.service_id", lines);

        if (nodeId != null && !nodeId.isBlank()) {
            sql.append("      AND LPAD(d.node_id::text, 5, '0') = LPAD(?::text, 5, '0')\n");
            params.add(nodeId);
        }

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
                ORDER BY
                    x.service_id,
                    x.node_name
                """);

        return jdbcTemplate.query(sql.toString(), this::mapRow, params.toArray());
    }

    /**
     * Returns available lines by mode.
     */
    public List<String> findLinesByMode(String mode) {
        String sql = """
                SELECT DISTINCT service_id
                FROM integrated_hourly_transit_demand_light
                WHERE mode = ?
                ORDER BY service_id
                """;

        return jdbcTemplate.queryForList(sql, String.class, mode);
    }

    /**
     * Groups catchment rows by physical node.
     *
     * The grouping key is intentionally limited to mode plus normalized nodeId.
     * Including nodeName or lat/lng in the key would split a single node into
     * multiple buckets whenever upstream sources disagree on naming or have
     * sub-decimal coordinate drift.
     */
    private List<NodeDemandResponse> groupRowsByNode(
            List<MapDemandResponse> rows,
            double centerLat,
            double centerLng
    ) {
        Map<String, MutableNodeDemand> grouped = new LinkedHashMap<>();

        for (MapDemandResponse row : rows) {
            String key = row.mode() + "|" + normalizeNodeId(row.nodeId());

            MutableNodeDemand value = grouped.computeIfAbsent(
                    key,
                    ignored -> new MutableNodeDemand(
                            row.mode(),
                            row.nodeId(),
                            row.nodeName(),
                            row.lat(),
                            row.lng(),
                            calculateDistanceMeters(centerLat, centerLng, row.lat(), row.lng())
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

    /**
     * Normalizes bus-style numeric node IDs to five digits.
     */
    private String normalizeNodeId(String nodeId) {
        if (nodeId == null) {
            return "";
        }

        String trimmed = nodeId.trim();

        if (trimmed.chars().allMatch(Character::isDigit)) {
            return String.format("%05d", Integer.parseInt(trimmed));
        }

        return trimmed;
    }

    /**
     * Returns the divisor used when selected day types should be averaged.
     */
    private int getDayAggregationDivisor(List<String> dayTypes, String dayAggregation) {
        if (!"average".equals(dayAggregation)) {
            return 1;
        }

        if (dayTypes == null || dayTypes.isEmpty()) {
            return 1;
        }

        return dayTypes.size();
    }

    /**
     * Builds the SQL expression used for sum or average-per-selected-day display.
     */
    private String buildDemandAggregationExpression(String columnName, int divisor) {
        if (divisor <= 1) {
            return "SUM(CAST(" + columnName + " AS BIGINT))";
        }

        return "ROUND(SUM(CAST(" + columnName + " AS NUMERIC)) / " + divisor + ")";
    }

    /**
     * Appends a parameterized IN clause.
     */
    private void appendInClause(StringBuilder sql, List<Object> params, String columnName, List<?> values) {
        if (values == null || values.isEmpty()) {
            return;
        }

        String placeholders = String.join(", ", values.stream().map(value -> "?").toList());

        sql.append("  AND ")
                .append(columnName)
                .append(" IN (")
                .append(placeholders)
                .append(")\n");

        params.addAll(values);
    }

    /**
     * Maps a SQL row into DTO.
     */
    private MapDemandResponse mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new MapDemandResponse(
                rs.getString("mode"),
                rs.getString("service_id"),
                rs.getString("node_id"),
                rs.getString("node_name"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                rs.getLong("boarding"),
                rs.getLong("alighting")
        );
    }

    /**
     * Calculates distance between two coordinates with the haversine formula.
     */
    private double calculateDistanceMeters(double firstLat, double firstLng, double secondLat, double secondLng) {
        double firstLatRadians = toRadians(firstLat);
        double secondLatRadians = toRadians(secondLat);
        double deltaLat = toRadians(secondLat - firstLat);
        double deltaLng = toRadians(secondLng - firstLng);

        double haversine =
                Math.sin(deltaLat / 2) * Math.sin(deltaLat / 2) +
                Math.cos(firstLatRadians) *
                        Math.cos(secondLatRadians) *
                        Math.sin(deltaLng / 2) *
                        Math.sin(deltaLng / 2);

        return EARTH_RADIUS_METERS * 2 * Math.atan2(
                Math.sqrt(haversine),
                Math.sqrt(1 - haversine)
        );
    }

    /**
     * Converts degrees to radians.
     */
    private double toRadians(double value) {
        return (value * Math.PI) / 180;
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

        private MutableNodeDemand(String mode, String nodeId, String nodeName, double lat, double lng, double distanceMeters) {
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
