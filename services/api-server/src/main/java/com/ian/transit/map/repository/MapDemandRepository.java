package com.ian.transit.map.repository;

import com.ian.transit.map.dto.MapDemandResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

    private final JdbcTemplate jdbcTemplate;

    public MapDemandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads map demand points by mode.
     *
     * Multiple selected hours and day types are aggregated in SQL so the map
     * renders one circle per stop/station instead of overlapping circles.
     */
    public List<MapDemandResponse> findMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines
    ) {
        if ("subway".equals(mode)) {
            return findSubwayMapDemand(mode, dayTypes, dayAggregation, hours, lines);
        }

        return findBusMapDemand(mode, dayTypes, dayAggregation, hours, lines);
    }

    /**
     * Loads subway demand points.
     *
     * Station names are normalized by removing parenthesized sub-names because
     * demand data and station master data sometimes store sub-names differently.
     */
    private List<MapDemandResponse> findSubwayMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines
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
                 AND regexp_replace(
                        d.node_name,
                        '\\(.*\\)',
                        '',
                        'g'
                     ) = regexp_replace(
                            s.station_name,
                            '\\(.*\\)',
                            '',
                            'g'
                     )
                WHERE d.mode = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(mode);

        appendInClause(sql, params, "d.day_type", dayTypes);
        appendInClause(sql, params, "d.hour", hours);
        appendInClause(sql, params, "d.service_id", lines);

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

        return jdbcTemplate.query(
                sql.toString(),
                this::mapRow,
                params.toArray()
        );
    }

    /**
     * Loads bus demand points from both coordinate sources.
     *
     * Existing Seoul bus stops continue to use bus_stop_location.
     * Metropolitan / outer-area stops that are missing from bus_stop_location
     * are added through the curated mapping tables.
     */
    private List<MapDemandResponse> findBusMapDemand(
            String mode,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            List<String> lines
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

                    -- Existing Seoul bus stop coordinates.
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

        sql.append("""

                    UNION ALL

                    -- Curated metropolitan / outer-area coordinate patch.
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

        return jdbcTemplate.query(
                sql.toString(),
                this::mapRow,
                params.toArray()
        );
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

        return jdbcTemplate.queryForList(
                sql,
                String.class,
                mode
        );
    }

    /**
     * Returns the divisor used when selected day types should be averaged.
     */
    private int getDayAggregationDivisor(
            List<String> dayTypes,
            String dayAggregation
    ) {
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
    private String buildDemandAggregationExpression(
            String columnName,
            int divisor
    ) {
        if (divisor <= 1) {
            return "SUM(CAST(" + columnName + " AS BIGINT))";
        }

        return "ROUND(SUM(CAST(" + columnName + " AS NUMERIC)) / " + divisor + ")";
    }

    /**
     * Appends a parameterized IN clause.
     *
     * Values are never interpolated into SQL text directly.
     * This keeps the dynamic query safe while still supporting multi-select filters.
     */
    private void appendInClause(
            StringBuilder sql,
            List<Object> params,
            String columnName,
            List<?> values
    ) {
        if (values == null || values.isEmpty()) {
            return;
        }

        String placeholders = String.join(
                ", ",
                values.stream().map(value -> "?").toList()
        );

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
    private MapDemandResponse mapRow(
            ResultSet rs,
            int rowNum
    ) throws SQLException {
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
