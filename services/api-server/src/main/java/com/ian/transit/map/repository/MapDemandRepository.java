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
 * regular expressions, and Korean column names.
 */
@Repository
public class MapDemandRepository {

    private final JdbcTemplate jdbcTemplate;

    public MapDemandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads map demand points by mode.
     */
    public List<MapDemandResponse> findMapDemand(
            String mode,
            String dayType,
            Integer hour,
            String line
    ) {
        if ("subway".equals(mode)) {
            return findSubwayMapDemand(mode, dayType, hour, normalizeLine(line));
        }

        return findBusMapDemand(mode, dayType, hour, normalizeLine(line));
    }

    /**
     * Loads subway demand points.
     *
     * The optional line filter is appended dynamically instead of using
     * "? IS NULL" because PostgreSQL cannot infer the type of an untyped
     * null parameter in that expression.
     */
    private List<MapDemandResponse> findSubwayMapDemand(
            String mode,
            String dayType,
            Integer hour,
            String line
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT
                    d.mode,
                    d.service_id,
                    d.node_id,
                    d.node_name,
                    s.lat,
                    s.lng,
                    CAST(d.boarding AS BIGINT) AS boarding,
                    CAST(d.alighting AS BIGINT) AS alighting
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
                  AND d.day_type = ?
                  AND d.hour = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(mode);
        params.add(dayType);
        params.add(hour);

        if (line != null) {
            sql.append("  AND d.service_id = ?\n");
            params.add(line);
        }

        sql.append("ORDER BY d.node_name");

        return jdbcTemplate.query(
                sql.toString(),
                this::mapRow,
                params.toArray()
        );
    }

    /**
     * Loads bus demand points using ARS stop numbers.
     *
     * The integrated demand table's node_id matches bus_stop_location."정류장번호".
     */
    private List<MapDemandResponse> findBusMapDemand(
            String mode,
            String dayType,
            Integer hour,
            String line
    ) {
        StringBuilder sql = new StringBuilder("""
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
                  AND d.day_type = ?
                  AND d.hour = ?
                """);

        List<Object> params = new ArrayList<>();
        params.add(mode);
        params.add(dayType);
        params.add(hour);

        if (line != null) {
            sql.append("  AND d.service_id = ?\n");
            params.add(line);
        }

        sql.append("ORDER BY d.node_name");

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
     * Converts blank line input into null so the repository can skip the line filter.
     */
    private String normalizeLine(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }

        return line.trim();
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
