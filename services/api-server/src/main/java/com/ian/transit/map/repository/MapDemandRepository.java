package com.ian.transit.map.repository;

import com.ian.transit.map.dto.MapDemandResponse;
import com.ian.transit.map.repository.support.DemandSqlSupport;
import com.ian.transit.map.repository.support.NodeIdNormalizer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for route-selected map demand point queries.
 *
 * This repository intentionally owns only map-layer demand rows and available
 * route lists. Node detail, catchment, and search responsibilities are split
 * into dedicated repositories.
 */
@Repository
public class MapDemandRepository {

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
     * Loads route-level demand rows restricted to a single node.
     */
    public List<MapDemandResponse> findMapDemandByNode(
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
     * Loads subway demand points.
     *
     * When nodeId is non-null, the query is pushed down to that single station.
     */
    private List<MapDemandResponse> findSubwayMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines,
            String nodeId
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

        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);
        DemandSqlSupport.appendInClause(sql, params, "d.service_id", lines);

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
     * When nodeId is non-null, the query is pushed down using LPAD-normalized comparison.
     */
    private List<MapDemandResponse> findBusMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines,
            String nodeId
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
        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);
        DemandSqlSupport.appendInClause(sql, params, "d.service_id", lines);

        if (nodeId != null && !nodeId.isBlank()) {
            sql.append("      AND LPAD(d.node_id::text, 5, '0') = LPAD(?::text, 5, '0')\n");
            params.add(NodeIdNormalizer.normalize(nodeId));
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
        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);
        DemandSqlSupport.appendInClause(sql, params, "d.service_id", lines);

        if (nodeId != null && !nodeId.isBlank()) {
            sql.append("      AND LPAD(d.node_id::text, 5, '0') = LPAD(?::text, 5, '0')\n");
            params.add(NodeIdNormalizer.normalize(nodeId));
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
}
