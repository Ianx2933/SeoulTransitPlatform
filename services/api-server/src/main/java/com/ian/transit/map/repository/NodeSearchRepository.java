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
 * Searches the stop and station location tables only. An earlier version
 * joined integrated_hourly_transit_demand_light on every search to return only
 * nodes with demand records; that table holds years of hourly demand, and the
 * join scanned all of it for each keyword (over ten minutes on db-f1-micro,
 * holding every pooled connection). A search result may now be a stop with no
 * demand rows; the node detail panel already shows "no demand" for that case.
 *
 * Implementation notes:
 * - Numeric columns (b."정류장번호", ars_id, demand_node_id) are cast to text
 *   before ILIKE comparison; without the cast PostgreSQL raises
 *   `operator does not exist: integer ~~* unknown`.
 * - LIKE wildcards (`%`, `_`, `\`) inside the user keyword are escaped so that
 *   accidental wildcard characters do not silently broaden the search.
 * - Bus node IDs are the ARS number padded to five digits, the same form the
 *   demand table uses, so node detail and catchment calls still match.
 * - Grouping is mode-aware:
 *     - subway is grouped by station name. Multiple lines serving the same
 *       transfer station collapse into one result at the centroid of the
 *       line entries.
 *     - bus is grouped by node ID. Opposite-direction stops that share a name
 *       but have different ARS numbers stay separate results.
 *     - the outer-area source (integrated_bus_stop_location) is reached through
 *       bus_stop_demand_node_mapping, which supplies its demand node IDs, and is
 *       skipped for IDs the primary bus_stop_location already covers.
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

        // The query unions three branches, each over a location table only:
        //   1) subway stations, grouped by station name (transfer-station collapse)
        //   2) bus stops from bus_stop_location, grouped by padded ARS number
        //   3) outer-area bus stops from integrated_bus_stop_location via the
        //      demand-node mapping, grouped by padded demand node ID
        String sql = """
                SELECT mode, node_id, node_name, lat, lng
                FROM (
                    -- ── Subway: one row per station name ──
                    SELECT
                        'subway'                AS mode,
                        MIN(s.station_name)     AS node_id,
                        s.station_name          AS node_name,
                        AVG(s.lat)              AS lat,
                        AVG(s.lng)              AS lng
                    FROM subway_station_location s
                    WHERE s.station_name ILIKE ? ESCAPE '\\'
                    GROUP BY s.station_name

                    UNION ALL

                    -- ── Bus (primary coordinate source): one row per ARS number ──
                    SELECT
                        'bus'                                   AS mode,
                        LPAD(b."정류장번호"::text, 5, '0')       AS node_id,
                        MIN(b."정류장명")                        AS node_name,
                        AVG(b."위도")                            AS lat,
                        AVG(b."경도")                            AS lng
                    FROM bus_stop_location b
                    WHERE LPAD(b."정류장번호"::text, 5, '0') = ?
                       OR b."정류장번호"::text ILIKE ? ESCAPE '\\'
                       OR b."정류장명"        ILIKE ? ESCAPE '\\'
                    GROUP BY LPAD(b."정류장번호"::text, 5, '0')

                    UNION ALL

                    -- ── Bus (curated outer-area source): one row per demand node ID ──
                    SELECT
                        'bus'                                       AS mode,
                        LPAD(m.demand_node_id::text, 5, '0')        AS node_id,
                        MIN(b.stop_name)                            AS node_name,
                        AVG(b.lat)                                  AS lat,
                        AVG(b.lng)                                  AS lng
                    FROM integrated_bus_stop_location b
                    JOIN bus_stop_demand_node_mapping m
                      ON m.canonical_node_id = b.canonical_node_id
                    WHERE (
                          LPAD(m.demand_node_id::text, 5, '0') = ?
                          OR b.ars_id::text ILIKE ? ESCAPE '\\'
                          OR b.stop_name    ILIKE ? ESCAPE '\\'
                      )
                      AND NOT EXISTS (
                          SELECT 1
                          FROM bus_stop_location existing
                          WHERE LPAD(existing."정류장번호"::text, 5, '0')
                              = LPAD(m.demand_node_id::text, 5, '0')
                      )
                    GROUP BY LPAD(m.demand_node_id::text, 5, '0')
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

        // subway branch: station name
        params.add(likeKeyword);

        // bus branch 1 (bus_stop_location): exact ID, ARS, name
        params.add(normalizedKeyword);
        params.add(likeKeyword);
        params.add(likeKeyword);

        // bus branch 2 (integrated_bus_stop_location): exact ID, ARS, name
        params.add(normalizedKeyword);
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
