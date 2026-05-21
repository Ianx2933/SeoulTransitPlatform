package com.ian.transit.map.repository;

import com.ian.transit.map.dto.NodeSearchResponse;
import com.ian.transit.map.repository.support.NodeIdNormalizer;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for searchable stop and station node candidates.
 *
 * Search results are limited to coordinate-matched nodes that are connected to
 * demand records, so selecting a result can safely open node detail/catchment.
 *
 * Implementation notes:
 * - Numeric columns (d.node_id, b."정류장번호") are explicitly cast to text before
 *   ILIKE comparison. Without the cast PostgreSQL raises
 *   `operator does not exist: integer ~~* unknown`.
 * - LIKE wildcards (`%`, `_`, `\`) inside the user keyword are escaped so that
 *   accidental wildcard characters do not silently broaden the search.
 * - Grouping is mode-aware:
 *     - subway is grouped by station name. Multiple lines serving the same
 *       transfer station collapse into one search result whose coordinate is
 *       the centroid of the contributing line entries.
 *     - bus is grouped by nodeId. Two physically distinct stops that happen
 *       to share a name (e.g. opposite-direction "강남역9번출구" stops with
 *       different ARS numbers) remain separate search results so the
 *       follow-up node-detail / node-catchment call uses a real coordinate.
 */
@Repository
public class NodeSearchRepository {

    private final JdbcTemplate jdbcTemplate;

    public NodeSearchRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Searches selectable stop or station nodes by name or ID.
     */
    public List<NodeSearchResponse> searchNodes(String keyword, int limit) {
        String escapedKeyword = escapeLikeWildcards(keyword);
        String likeKeyword = "%" + escapedKeyword + "%";
        String normalizedKeyword = NodeIdNormalizer.normalize(keyword);

        // The query unions three branches:
        //   1) subway, pre-grouped by station name (transfer-station collapse)
        //   2) bus from bus_stop_location, pre-grouped by LPAD'd nodeId
        //   3) bus from integrated_bus_stop_location, pre-grouped by LPAD'd nodeId
        // Pre-grouping inside each branch makes the UNION cheaper and lets the
        // outer ORDER BY operate on already-deduplicated rows.
        String sql = """
                SELECT mode, node_id, node_name, lat, lng
                FROM (
                    -- ── Subway: collapse transfer stations to one row per station name ──
                    SELECT
                        'subway'                                                AS mode,
                        MIN(s.station_name)                                     AS node_id,
                        s.station_name                                          AS node_name,
                        AVG(s.lat)                                              AS lat,
                        AVG(s.lng)                                              AS lng
                    FROM integrated_hourly_transit_demand_light d
                    JOIN subway_station_location s
                      ON d.service_id = s.line_name
                     AND regexp_replace(d.node_name, '\\(.*\\)', '', 'g')
                       = regexp_replace(s.station_name, '\\(.*\\)', '', 'g')
                    WHERE d.mode = 'subway'
                      AND (
                          d.node_id::text ILIKE ? ESCAPE '\\'
                          OR d.node_name  ILIKE ? ESCAPE '\\'
                          OR s.station_name ILIKE ? ESCAPE '\\'
                      )
                    GROUP BY s.station_name

                    UNION ALL

                    -- ── Bus (primary coordinate source): keep nodeId distinct ──
                    SELECT
                        'bus'                                                   AS mode,
                        LPAD(d.node_id::text, 5, '0')                           AS node_id,
                        MIN(b."정류장명")                                        AS node_name,
                        AVG(b."위도")                                            AS lat,
                        AVG(b."경도")                                            AS lng
                    FROM integrated_hourly_transit_demand_light d
                    JOIN bus_stop_location b
                      ON LPAD(d.node_id::text, 5, '0')
                       = LPAD(b."정류장번호"::text, 5, '0')
                    WHERE d.mode = 'bus'
                      AND (
                          LPAD(d.node_id::text, 5, '0') = ?
                          OR d.node_id::text       ILIKE ? ESCAPE '\\'
                          OR b."정류장번호"::text   ILIKE ? ESCAPE '\\'
                          OR b."정류장명"          ILIKE ? ESCAPE '\\'
                      )
                    GROUP BY LPAD(d.node_id::text, 5, '0')

                    UNION ALL

                    -- ── Bus (curated outer-area source): keep nodeId distinct ──
                    SELECT
                        'bus'                                                   AS mode,
                        LPAD(d.node_id::text, 5, '0')                           AS node_id,
                        MIN(b.stop_name)                                        AS node_name,
                        AVG(b.lat)                                              AS lat,
                        AVG(b.lng)                                              AS lng
                    FROM integrated_hourly_transit_demand_light d
                    JOIN bus_stop_demand_node_mapping m
                      ON d.service_id = m.service_id
                     AND LPAD(d.node_id::text, 5, '0')
                       = LPAD(m.demand_node_id::text, 5, '0')
                    JOIN integrated_bus_stop_location b
                      ON m.canonical_node_id = b.canonical_node_id
                    WHERE d.mode = 'bus'
                      AND (
                          LPAD(d.node_id::text, 5, '0') = ?
                          OR d.node_id::text  ILIKE ? ESCAPE '\\'
                          OR b.ars_id::text   ILIKE ? ESCAPE '\\'
                          OR b.stop_name      ILIKE ? ESCAPE '\\'
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM bus_stop_location existing
                          WHERE LPAD(d.node_id::text, 5, '0')
                              = LPAD(existing."정류장번호"::text, 5, '0')
                      )
                    GROUP BY LPAD(d.node_id::text, 5, '0')
                ) nodes
                ORDER BY
                    CASE WHEN node_name = ? THEN 0 ELSE 1 END,
                    CASE WHEN node_id   = ? THEN 0 ELSE 1 END,
                    CASE WHEN mode = 'subway' THEN 0 ELSE 1 END,
                    node_name,
                    node_id
                LIMIT ?
                """;

        List<Object> params = new ArrayList<>();

        // subway branch: 3 ILIKE params
        params.add(likeKeyword);
        params.add(likeKeyword);
        params.add(likeKeyword);

        // bus branch 1 (bus_stop_location): 1 exact + 3 ILIKE
        params.add(normalizedKeyword);
        params.add(likeKeyword);
        params.add(likeKeyword);
        params.add(likeKeyword);

        // bus branch 2 (integrated_bus_stop_location): 1 exact + 3 ILIKE
        params.add(normalizedKeyword);
        params.add(likeKeyword);
        params.add(likeKeyword);
        params.add(likeKeyword);

        // outer ORDER BY: exact-name match, then exact-id match
        params.add(keyword.trim());
        params.add(normalizedKeyword);

        // LIMIT
        params.add(limit);

        return jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new NodeSearchResponse(
                        rs.getString("mode"),
                        rs.getString("node_id"),
                        rs.getString("node_name"),
                        rs.getDouble("lat"),
                        rs.getDouble("lng")
                ),
                params.toArray()
        );
    }

    /**
     * Escapes LIKE wildcards inside user input.
     *
     * Without escaping a keyword like "50%" would silently broaden the search
     * to anything containing "50". Backslash must be escaped first so it does
     * not double-escape later replacements.
     */
    private String escapeLikeWildcards(String keyword) {
        return keyword
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
