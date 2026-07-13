package com.ian.transit.map.repository;

import com.ian.transit.map.dto.DistrictDemandResponse;
import com.ian.transit.map.repository.support.DemandSqlSupport;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Repository for district-centered all-route demand queries.
 *
 * This query uses administrative polygons only as a node-location filter. It
 * does not estimate OD flows, transfers, onboard load, or crowding.
 *
 * Node-id note: this repository normalizes bus node ids with
 * LPAD(node_id, 5, '0'), matching earlier phases. PostgreSQL LPAD TRUNCATES
 * strings longer than 5 characters, so all bus node ids in the source data
 * are assumed to be at most 5 digits (Seoul/Gyeonggi ARS numbers). If longer
 * ids are ever ingested, this normalization must be revisited before use.
 */
@Repository
public class DistrictDemandRepository {

    private final JdbcTemplate jdbcTemplate;

    public DistrictDemandRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Loads all-route demand for nodes located in selected districts.
     *
     * @param nodeLimit optional cap on returned node rows; totals and
     *                  breakdowns are always computed from ALL rows before
     *                  truncation, so limiting never changes aggregates.
     */
    public DistrictDemandResponse findDistrictDemand(
            List<String> districtCodes,
            List<String> modes,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours,
            Integer nodeLimit
    ) {
        List<DistrictDemandResponse.DistrictSummary> districts = findDistrictSummaries(districtCodes);
        List<String> unmatchedDistrictCodes = findUnmatchedDistrictCodes(districtCodes, districts);

        List<DistrictDemandResponse.NodeDemand> nodes = findNodeDemandRows(
                districtCodes,
                modes,
                dayTypes,
                dayAggregation,
                hours
        );

        long boarding = nodes.stream().mapToLong(DistrictDemandResponse.NodeDemand::boarding).sum();
        long alighting = nodes.stream().mapToLong(DistrictDemandResponse.NodeDemand::alighting).sum();

        List<DistrictDemandResponse.ModeDemand> modeBreakdown = buildModeBreakdown(nodes);
        List<DistrictDemandResponse.RouteDemand> routeBreakdown = buildRouteBreakdown(nodes);

        long totalNodeCount = nodes.size();
        List<DistrictDemandResponse.NodeDemand> limitedNodes = limitNodes(nodes, nodeLimit);

        return new DistrictDemandResponse(
                districts,
                unmatchedDistrictCodes,
                boarding,
                alighting,
                boarding + alighting,
                modeBreakdown,
                routeBreakdown,
                limitedNodes,
                totalNodeCount
        );
    }

    /**
     * Returns selected district labels even when no demand rows exist.
     */
    private List<DistrictDemandResponse.DistrictSummary> findDistrictSummaries(List<String> districtCodes) {
        List<Object> params = new ArrayList<>(districtCodes);
        String placeholders = String.join(
                ", ",
                districtCodes.stream().map(value -> "?").toList()
        );

        String sql = """
                SELECT
                    adm_cd AS district_code,
                    adm_nm AS district_name
                FROM public.admin_dong_boundary
                WHERE adm_cd IN (%s)
                ORDER BY adm_cd
                """.formatted(placeholders);

        return jdbcTemplate.query(sql, this::mapDistrictSummary, params.toArray());
    }

    /**
     * Detects requested codes that matched no boundary row, so code-system
     * mismatches (e.g. 8-digit vs 10-digit adm_cd) surface instead of being
     * silently reported as zero demand.
     */
    private List<String> findUnmatchedDistrictCodes(
            List<String> requestedDistrictCodes,
            List<DistrictDemandResponse.DistrictSummary> matchedDistricts
    ) {
        Set<String> matchedCodes = new LinkedHashSet<>();
        matchedDistricts.forEach(district -> matchedCodes.add(district.districtCode()));

        return requestedDistrictCodes.stream()
                .filter(code -> !matchedCodes.contains(code))
                .toList();
    }

    /** Loads route-node rows and pushes day/hour aggregation down to SQL. */
    private List<DistrictDemandResponse.NodeDemand> findNodeDemandRows(
            List<String> districtCodes,
            List<String> modes,
            List<String> dayTypes,
            String dayAggregation,
            List<Integer> hours
    ) {
        int divisor = DemandSqlSupport.getDayAggregationDivisor(dayTypes, dayAggregation);
        String boardingExpression = DemandSqlSupport.buildDemandAggregationExpression("x.boarding", divisor);
        String alightingExpression = DemandSqlSupport.buildDemandAggregationExpression("x.alighting", divisor);

        List<String> branches = new ArrayList<>();
        List<Object> branchParams = new ArrayList<>();

        if (modes.contains("subway")) {
            branches.add(buildSubwayBranch(branchParams, dayTypes, hours));
        }

        if (modes.contains("bus")) {
            branches.add(buildPrimaryBusBranch(branchParams, dayTypes, hours));
            branches.add(buildIntegratedBusBranch(branchParams, dayTypes, hours));
        }

        if (branches.isEmpty()) {
            return List.of();
        }

        List<Object> params = new ArrayList<>(districtCodes);
        params.addAll(branchParams);

        String districtPlaceholders = String.join(
                ", ",
                districtCodes.stream().map(value -> "?").toList()
        );

        StringBuilder sql = new StringBuilder("""
                WITH district_scope AS (
                    SELECT
                        adm_cd,
                        adm_nm,
                        geom
                    FROM public.admin_dong_boundary
                    WHERE adm_cd IN (%s)
                ), base_rows AS (
                """.formatted(districtPlaceholders));

        sql.append(String.join("\nUNION ALL\n", branches));
        sql.append("""
                )
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
        // ORDER BY uses the raw sum rather than the (possibly divided) average
        // expression. The divisor is a constant per request, so the ranking is
        sql.append("""
                FROM base_rows x
                GROUP BY
                    x.mode,
                    x.service_id,
                    x.node_id,
                    x.node_name,
                    x.lat,
                    x.lng
                ORDER BY
                    (SUM(CAST(x.boarding AS BIGINT)) + SUM(CAST(x.alighting AS BIGINT))) DESC,
                    x.mode,
                    x.service_id,
                    x.node_name
                """);

        return jdbcTemplate.query(sql.toString(), this::mapNodeDemand, params.toArray());
    }

    /**
     * Builds the subway branch using station coordinates.
     *
     * The station-location side is deduplicated with DISTINCT ON per
     * (line, cleaned station name) so duplicate coordinate rows can never fan
     * out demand rows. The district filter uses EXISTS instead of JOIN so a
     * node lying exactly on a shared boundary of two selected districts is
     * counted once.
     */
    private String buildSubwayBranch(
            List<Object> params,
            List<String> dayTypes,
            List<Integer> hours
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
                    JOIN (
                        SELECT DISTINCT ON (
                            line_name,
                            regexp_replace(station_name, '\\([^)]*\\)', '', 'g')
                        )
                            line_name,
                            regexp_replace(station_name, '\\([^)]*\\)', '', 'g') AS station_key,
                            lat,
                            lng
                        FROM subway_station_location
                        ORDER BY
                            line_name,
                            regexp_replace(station_name, '\\([^)]*\\)', '', 'g'),
                            lat,
                            lng
                    ) s
                      ON d.service_id = s.line_name
                     AND regexp_replace(d.node_name, '\\([^)]*\\)', '', 'g') = s.station_key
                    WHERE d.mode = 'subway'
                      AND EXISTS (
                          SELECT 1
                          FROM district_scope ds
                          WHERE ST_Covers(
                              ds.geom,
                              ST_SetSRID(ST_MakePoint(s.lng, s.lat), 4326)
                          )
                      )
                """);

        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);
        return sql.toString();
    }

    /**
     * Builds the direct bus-stop branch.
     *
     * bus_stop_location is deduplicated per normalized stop number so duplicate
     * source rows cannot multiply demand. District filtering uses EXISTS for
     * the same shared-boundary reason as the subway branch.
     */
    private String buildPrimaryBusBranch(
            List<Object> params,
            List<String> dayTypes,
            List<Integer> hours
    ) {
        StringBuilder sql = new StringBuilder("""
                    SELECT
                        d.mode,
                        d.service_id,
                        LPAD(d.node_id::text, 5, '0') AS node_id,
                        b.node_name,
                        b.lat,
                        b.lng,
                        CAST(d.boarding AS BIGINT) AS boarding,
                        CAST(d.alighting AS BIGINT) AS alighting
                    FROM integrated_hourly_transit_demand_light d
                    JOIN (
                        SELECT DISTINCT ON (LPAD("정류장번호"::text, 5, '0'))
                            LPAD("정류장번호"::text, 5, '0') AS stop_key,
                            "정류장명" AS node_name,
                            "위도" AS lat,
                            "경도" AS lng,
                            COALESCE(
                                geom,
                                ST_SetSRID(ST_MakePoint("경도", "위도"), 4326)
                            ) AS point_geom
                        FROM bus_stop_location
                        ORDER BY LPAD("정류장번호"::text, 5, '0'), "정류장명"
                    ) b
                      ON LPAD(d.node_id::text, 5, '0') = b.stop_key
                    WHERE d.mode = 'bus'
                      AND LPAD(d.node_id::text, 5, '0') <> '00000'
                      AND EXISTS (
                          SELECT 1
                          FROM district_scope ds
                          WHERE ST_Covers(ds.geom, b.point_geom)
                      )
                """);

        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);
        return sql.toString();
    }

    /**
     * Builds the integrated bus-stop branch for missing direct coordinates.
     *
     * The mapping table is deduplicated per (service_id, normalized demand
     * node id) so multi-mapped rows cannot fan out demand.
     */
    private String buildIntegratedBusBranch(
            List<Object> params,
            List<String> dayTypes,
            List<Integer> hours
    ) {
        StringBuilder sql = new StringBuilder("""
                    SELECT
                        d.mode,
                        d.service_id,
                        LPAD(d.node_id::text, 5, '0') AS node_id,
                        b.stop_name AS node_name,
                        b.lat,
                        b.lng,
                        CAST(d.boarding AS BIGINT) AS boarding,
                        CAST(d.alighting AS BIGINT) AS alighting
                    FROM integrated_hourly_transit_demand_light d
                    JOIN (
                        SELECT DISTINCT ON (
                            service_id,
                            LPAD(demand_node_id::text, 5, '0')
                        )
                            service_id,
                            LPAD(demand_node_id::text, 5, '0') AS demand_node_key,
                            canonical_node_id
                        FROM bus_stop_demand_node_mapping
                        ORDER BY
                            service_id,
                            LPAD(demand_node_id::text, 5, '0'),
                            canonical_node_id
                    ) m
                      ON d.service_id = m.service_id
                     AND LPAD(d.node_id::text, 5, '0') = m.demand_node_key
                    JOIN integrated_bus_stop_location b
                      ON m.canonical_node_id = b.canonical_node_id
                    WHERE d.mode = 'bus'
                      AND LPAD(d.node_id::text, 5, '0') <> '00000'
                      AND NOT EXISTS (
                          SELECT 1
                          FROM bus_stop_location existing
                          WHERE LPAD(d.node_id::text, 5, '0')
                              = LPAD(existing."정류장번호"::text, 5, '0')
                            AND LPAD(existing."정류장번호"::text, 5, '0') <> '00000'
                      )
                      AND EXISTS (
                          SELECT 1
                          FROM district_scope ds
                          WHERE ST_Covers(
                              ds.geom,
                              COALESCE(
                                  b.geom,
                                  ST_SetSRID(ST_MakePoint(b.lng, b.lat), 4326)
                              )
                          )
                      )
                """);

        DemandSqlSupport.appendInClause(sql, params, "d.day_type", dayTypes);
        DemandSqlSupport.appendInClause(sql, params, "d.hour", hours);
        return sql.toString();
    }

    /** Truncates node rows after aggregation. */
    private List<DistrictDemandResponse.NodeDemand> limitNodes(
            List<DistrictDemandResponse.NodeDemand> nodes,
            Integer nodeLimit
    ) {
        if (nodeLimit == null || nodes.size() <= nodeLimit) {
            return nodes;
        }
        return List.copyOf(nodes.subList(0, nodeLimit));
    }

    /** Builds mode totals from route-node rows. */
    private List<DistrictDemandResponse.ModeDemand> buildModeBreakdown(
            List<DistrictDemandResponse.NodeDemand> nodes
    ) {
        Map<String, DemandAccumulator> accumulatorByMode = new LinkedHashMap<>();

        nodes.forEach(node -> accumulatorByMode
                .computeIfAbsent(node.mode(), key -> new DemandAccumulator())
                .add(node.boarding(), node.alighting()));

        return accumulatorByMode.entrySet().stream()
                .map(entry -> new DistrictDemandResponse.ModeDemand(
                        entry.getKey(),
                        entry.getValue().boarding(),
                        entry.getValue().alighting(),
                        entry.getValue().total()
                ))
                .sorted(Comparator.comparingLong(DistrictDemandResponse.ModeDemand::total).reversed())
                .toList();
    }

    /** Builds route totals from route-node rows. */
    private List<DistrictDemandResponse.RouteDemand> buildRouteBreakdown(
            List<DistrictDemandResponse.NodeDemand> nodes
    ) {
        Map<String, RouteAccumulator> accumulatorByRoute = new LinkedHashMap<>();

        nodes.forEach(node -> {
            String key = node.mode() + "-" + node.serviceId();
            accumulatorByRoute
                    .computeIfAbsent(key, unused -> new RouteAccumulator(node.mode(), node.serviceId()))
                    .add(node.boarding(), node.alighting());
        });

        return accumulatorByRoute.values().stream()
                .map(accumulator -> new DistrictDemandResponse.RouteDemand(
                        accumulator.mode(),
                        accumulator.serviceId(),
                        accumulator.boarding(),
                        accumulator.alighting(),
                        accumulator.total()
                ))
                .sorted(Comparator.comparingLong(DistrictDemandResponse.RouteDemand::total).reversed())
                .toList();
    }

    private DistrictDemandResponse.DistrictSummary mapDistrictSummary(ResultSet rs, int rowNum)
            throws SQLException {
        return new DistrictDemandResponse.DistrictSummary(
                rs.getString("district_code"),
                rs.getString("district_name")
        );
    }

    private DistrictDemandResponse.NodeDemand mapNodeDemand(ResultSet rs, int rowNum)
            throws SQLException {
        long boarding = rs.getLong("boarding");
        long alighting = rs.getLong("alighting");
        return new DistrictDemandResponse.NodeDemand(
                rs.getString("mode"),
                rs.getString("service_id"),
                rs.getString("node_id"),
                rs.getString("node_name"),
                rs.getDouble("lat"),
                rs.getDouble("lng"),
                boarding,
                alighting,
                boarding + alighting
        );
    }

    /** Mutable demand sum holder. */
    private static final class DemandAccumulator {
        private long boarding;
        private long alighting;

        private void add(long nextBoarding, long nextAlighting) {
            boarding += nextBoarding;
            alighting += nextAlighting;
        }

        private long boarding() {
            return boarding;
        }

        private long alighting() {
            return alighting;
        }

        private long total() {
            return boarding + alighting;
        }
    }

    /** Route demand sum holder using composition instead of inheritance. */
    private static final class RouteAccumulator {
        private final String mode;
        private final String serviceId;
        private final DemandAccumulator demand = new DemandAccumulator();

        private RouteAccumulator(String mode, String serviceId) {
            this.mode = mode;
            this.serviceId = serviceId;
        }

        private void add(long nextBoarding, long nextAlighting) {
            demand.add(nextBoarding, nextAlighting);
        }

        private String mode() {
            return mode;
        }

        private String serviceId() {
            return serviceId;
        }

        private long boarding() {
            return demand.boarding();
        }

        private long alighting() {
            return demand.alighting();
        }

        private long total() {
            return demand.total();
        }
    }
}
