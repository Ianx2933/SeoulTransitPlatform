package com.ian.transit.terrain.infrastructure;

import com.ian.transit.terrain.api.TerrainUnavailableException;
import com.ian.transit.terrain.model.DongAccessibilityRow;
import com.ian.transit.terrain.model.StopTerrainRow;
import com.ian.transit.terrain.model.TerrainDataset;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import lombok.RequiredArgsConstructor;

/**
 * Reads an immutable published snapshot; no runtime spatial join.
 *
 * The stop-to-dong assignment is resolved once at publication time and stored
 * one row per stop, so neither query can multiply rows when a stop sits on a
 * shared boundary edge.
 *
 * JdbcTemplate matches the other query repositories in this project and keeps
 * the conditional aggregates explicit.
 */
@Repository
@RequiredArgsConstructor
public class TerrainQueryRepository {
    private final JdbcTemplate jdbcTemplate;

    private static final String ACTIVE_SQL = """
            SELECT d.dataset_id, d.source_id, d.slope_method, d.boundary_base_date,
                   d.processed_at, d.published_at, a.activated_at,
                   d.stop_count, d.measured_stop_count, d.assigned_stop_count,
                   d.ambiguous_boundary_stop_count
            FROM public.terrain_active_dataset a
            JOIN public.terrain_dataset d ON d.dataset_id = a.dataset_id
            WHERE a.singleton = TRUE AND d.published_at IS NOT NULL
            """;

    public TerrainDataset requireActiveDataset() {
        List<TerrainDataset> rows = jdbcTemplate.query(ACTIVE_SQL, (rs, n) ->
                new TerrainDataset(rs.getString("dataset_id"), rs.getString("source_id"),
                        rs.getString("slope_method"), rs.getString("boundary_base_date"),
                        rs.getTimestamp("processed_at").toInstant(),
                        rs.getTimestamp("published_at").toInstant(),
                        rs.getTimestamp("activated_at").toInstant(),
                        rs.getLong("stop_count"), rs.getLong("measured_stop_count"),
                        rs.getLong("assigned_stop_count"), rs.getLong("ambiguous_boundary_stop_count")));
        if (rows.isEmpty()) throw new TerrainUnavailableException("No published terrain dataset is active");
        return rows.get(0);
    }

    public List<StopTerrainRow> findStopsAboveSlope(String datasetId, double minSlope, int limit, int offset) {
        return jdbcTemplate.query("""
                SELECT s.node_id, s.stop_no, s.stop_name, ST_X(s.geom) AS longitude,
                       ST_Y(s.geom) AS latitude, s.elev_m, s.slope_pct,
                       a.adm_cd, a.adm_nm, a.assignment_method, a.candidate_count
                FROM public.bus_stop_terrain_stats s
                LEFT JOIN public.terrain_stop_dong_assignment a
                  ON a.dataset_id = s.dataset_id AND a.node_id = s.node_id
                WHERE s.dataset_id = ? AND s.slope_pct IS NOT NULL AND s.slope_pct >= ?
                ORDER BY s.slope_pct DESC, s.node_id COLLATE "C" ASC
                LIMIT ? OFFSET ?
                """, this::mapStop, datasetId, minSlope, limit, offset);
    }

    public List<DongAccessibilityRow> summariseByDong(String datasetId, double minSlope, int minStops) {
        return jdbcTemplate.query("""
                SELECT a.adm_cd, MIN(a.adm_nm) AS adm_nm,
                       COUNT(*) AS total_stops,
                       COUNT(s.slope_pct) AS measured_stops,
                       COUNT(*) - COUNT(s.slope_pct) AS missing_slope_stops,
                       COUNT(*) FILTER (WHERE s.slope_pct >= ?) AS steep_stops,
                       ROUND(AVG(s.slope_pct)::numeric, 2) AS avg_slope_pct,
                       ROUND(MAX(s.slope_pct)::numeric, 2) AS max_slope_pct,
                       ROUND(AVG(s.elev_m)::numeric, 1) AS avg_elev_m
                FROM public.terrain_stop_dong_assignment a
                JOIN public.bus_stop_terrain_stats s
                  ON s.dataset_id = a.dataset_id AND s.node_id = a.node_id
                WHERE a.dataset_id = ? AND a.adm_cd IS NOT NULL
                GROUP BY a.adm_cd
                HAVING COUNT(s.slope_pct) >= ?
                ORDER BY COUNT(*) FILTER (WHERE s.slope_pct >= ?)::numeric
                           / NULLIF(COUNT(s.slope_pct), 0) DESC NULLS LAST,
                         COUNT(s.slope_pct) DESC, a.adm_cd COLLATE "C" ASC
                """, this::mapDong, minSlope, datasetId, minStops, minSlope);
    }

    private StopTerrainRow mapStop(ResultSet rs, int rowNum) throws SQLException {
        return new StopTerrainRow(rs.getString("node_id"), rs.getString("stop_no"),
                rs.getString("stop_name"), rs.getDouble("longitude"), rs.getDouble("latitude"),
                nullableDouble(rs, "elev_m"), rs.getDouble("slope_pct"),
                rs.getString("adm_cd"), rs.getString("adm_nm"), rs.getString("assignment_method"),
                rs.getInt("candidate_count"));
    }

    private DongAccessibilityRow mapDong(ResultSet rs, int rowNum) throws SQLException {
        return new DongAccessibilityRow(rs.getString("adm_cd"), rs.getString("adm_nm"),
                rs.getLong("total_stops"), rs.getLong("measured_stops"),
                rs.getLong("missing_slope_stops"), rs.getLong("steep_stops"),
                nullableDouble(rs, "avg_slope_pct"), nullableDouble(rs, "max_slope_pct"),
                nullableDouble(rs, "avg_elev_m"));
    }

    private static Double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? null : value;
    }
}
