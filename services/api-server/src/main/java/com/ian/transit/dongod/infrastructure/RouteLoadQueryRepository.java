package com.ian.transit.dongod.infrastructure;

import com.ian.transit.dongod.application.DongArea;
import com.ian.transit.dongod.application.RouteLoadCalculator;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads what the area route load needs: the route OD legs touching an area,
 * the stored stations of those routes, and their segment geometry
 * (bus_route_path_*, filled by pipelines/bus_route_path).
 *
 * Stations and segments are kept in memory per route for 12 hours: they
 * change once a week (Tuesday), every district or route view reads dozens
 * of routes, and reading their geometry on each request was most of the disk
 * reads that capped the load test of 2026-11-20 at under one request a
 * second.
 */
@Repository
@RequiredArgsConstructor
public class RouteLoadQueryRepository {

    /** Geometry simplification for the map, in degrees (about 5 m). */
    private static final double SIMPLIFY_TOLERANCE = 0.00005;

    /**
     * Stops inside the area as ARS keys with and without leading zeros, from
     * stop_area_any (database/schema/11_stop_area_lookup.sql: every stop point
     * of bus_stop_location and integrated_bus_stop_location, stop_location_fix
     * replacing both for its ARS, placed in its 행정동 once instead of per
     * request). %s is the area filter on adm_cd. Shared with
     * DongOdQueryRepository (bus routes tab, bus reach outside Seoul).
     */
    static final String AREA_STOPS = """
            WITH stops AS MATERIALIZED (
                SELECT DISTINCT ars FROM stop_area_any WHERE %s
            ),
            stop_keys AS MATERIALIZED (
                SELECT ars FROM stops WHERE ars <> '00000'
                UNION
                SELECT LTRIM(ars, '0') FROM stops WHERE ars <> '00000'
            )
            """;

    private static final String BOARD = """
            SELECT 노선명 AS route, 승차_정류장ars AS b, 하차_정류장ars AS a,
                   승차_정류장순번 AS bq, 하차_정류장순번 AS aq, SUM(승객수) AS p
            FROM od_typical_route_stop
            WHERE 기준일자 = :label AND 승차_정류장ars IN (SELECT ars FROM stop_keys)
            GROUP BY 1, 2, 3, 4, 5
            """;

    private static final String ALIGHT = """
            SELECT 노선명 AS route, 승차_정류장ars AS b, 하차_정류장ars AS a,
                   승차_정류장순번 AS bq, 하차_정류장순번 AS aq, SUM(승객수) AS p
            FROM od_typical_route_stop
            WHERE 기준일자 = :label AND 하차_정류장ars IN (SELECT ars FROM stop_keys)
            GROUP BY 1, 2, 3, 4, 5
            """;

    private static final String STATIONS_SQL = """
            SELECT r.od_route_nm, s.route_nm, s.seq, s.ars
            FROM bus_route_path_station s
            JOIN bus_route_path_route r ON r.route_nm = s.route_nm
            WHERE r.status = 'ok' AND r.od_route_nm IN (:routes)
            """;

    /** Segments with the names of their two stops (the next stored station, seq may skip). */
    private static final String SEGMENTS_SQL = """
            SELECT s.route_nm, s.seq, s.from_ars, s.to_ars,
                   a.station_nm AS from_name, b.station_nm AS to_name,
                   ST_AsText(ST_SimplifyPreserveTopology(s.geom, :tolerance)) AS wkt
            FROM bus_route_path_segment s
            LEFT JOIN bus_route_path_station a ON a.route_nm = s.route_nm AND a.seq = s.seq
            LEFT JOIN LATERAL (
                SELECT n.station_nm
                FROM bus_route_path_station n
                WHERE n.route_nm = s.route_nm AND n.seq > s.seq
                ORDER BY n.seq
                LIMIT 1
            ) b ON true
            WHERE s.route_nm IN (:variants)
            """;

    private final NamedParameterJdbcTemplate jdbc;

    private final Cache<String, List<RouteLoadCalculator.Station>> stationCache = Caffeine.newBuilder()
            .maximumSize(3_000).expireAfterWrite(Duration.ofHours(12)).build();
    /** Weighed by map points kept (about 1.5 million points, some 70 MB of heap at most). */
    private final Cache<String, List<SegmentRow>> segmentCache = Caffeine.newBuilder()
            .maximumWeight(1_500_000)
            .weigher((String variant, List<SegmentRow> rows) ->
                    1 + rows.stream().mapToInt(r -> r.path().size()).sum())
            .expireAfterWrite(Duration.ofHours(12)).build();

    public record SegmentRow(String variant, int seq, String fromArs, String toArs, List<double[]> path,
                             String fromName, String toName) {

        public SegmentRow(String variant, int seq, String fromArs, String toArs, List<double[]> path) {
            this(variant, seq, fromArs, toArs, path, null, null);
        }
    }

    /**
     * Legs of the area on the day label. direction od = boarding in the area,
     * do = alighting in it, both = either (UNION keeps a trip inside the area once:
     * the grouped rows are unique per route and stop pair).
     */
    public List<RouteLoadCalculator.Leg> legs(String label, DongArea level, String code, String direction) {
        if (level == DongArea.CITY) {
            throw new IllegalArgumentException("route load is for a dong or a gu, not all of Seoul");
        }
        String body = switch (direction) {
            case "od" -> BOARD;
            case "do" -> ALIGHT;
            default -> BOARD + " UNION " + ALIGHT;
        };
        String sql = AREA_STOPS.formatted(level.filterSql("adm_cd")) + body;
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("label", label).addValues(level.params(code));
        return jdbc.query(sql, p, (rs, i) -> new RouteLoadCalculator.Leg(
                rs.getString("route"), rs.getString("b"), rs.getString("a"), rs.getDouble("p"),
                rs.getObject("bq", Integer.class), rs.getObject("aq", Integer.class)));
    }

    /** The area's stop keys (AREA_STOPS), for a period query in BigQuery. */
    public List<String> areaStopKeys(DongArea level, String code) {
        if (level == DongArea.CITY) {
            throw new IllegalArgumentException("route load is for a dong or a gu, not all of Seoul");
        }
        String sql = AREA_STOPS.formatted(level.filterSql("adm_cd")) + " SELECT ars FROM stop_keys ORDER BY ars";
        return jdbc.queryForList(sql, new MapSqlParameterSource().addValues(level.params(code)), String.class);
    }

    /** Stored stations of the OD routes (all their variants), cached per OD route. */
    public List<RouteLoadCalculator.Station> stations(Collection<String> routes) {
        if (routes.isEmpty()) {
            return List.of();
        }
        return stationCache.getAll(new LinkedHashSet<>(routes), this::loadStations).values().stream()
                .flatMap(List::stream).toList();
    }

    private Map<String, List<RouteLoadCalculator.Station>> loadStations(Set<? extends String> routes) {
        Map<String, List<RouteLoadCalculator.Station>> out = new HashMap<>();
        routes.forEach(r -> out.put(r, new ArrayList<>()));
        jdbc.query(STATIONS_SQL, new MapSqlParameterSource("routes", List.copyOf(routes)), rs -> {
            var s = new RouteLoadCalculator.Station(rs.getString("od_route_nm"), rs.getString("route_nm"),
                    rs.getInt("seq"), rs.getString("ars"));
            out.computeIfAbsent(s.odRoute(), k -> new ArrayList<>()).add(s);
        });
        return out;
    }

    /** Segments of the stored variants with simplified geometry, cached per variant. */
    public List<SegmentRow> segments(Collection<String> variants) {
        if (variants.isEmpty()) {
            return List.of();
        }
        return segmentCache.getAll(new LinkedHashSet<>(variants), this::loadSegments).values().stream()
                .flatMap(List::stream).toList();
    }

    private Map<String, List<SegmentRow>> loadSegments(Set<? extends String> variants) {
        Map<String, List<SegmentRow>> out = new HashMap<>();
        variants.forEach(v -> out.put(v, new ArrayList<>()));
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("variants", List.copyOf(variants))
                .addValue("tolerance", SIMPLIFY_TOLERANCE);
        jdbc.query(SEGMENTS_SQL, p, rs -> {
            var row = new SegmentRow(rs.getString("route_nm"), rs.getInt("seq"),
                    rs.getString("from_ars"), rs.getString("to_ars"), parseLineString(rs.getString("wkt")),
                    rs.getString("from_name"), rs.getString("to_name"));
            out.computeIfAbsent(row.variant(), k -> new ArrayList<>()).add(row);
        });
        return out;
    }

    /** [lat, lng] pairs of a WKT LINESTRING, rounded to 6 decimals; empty for anything else. */
    public static List<double[]> parseLineString(String wkt) {
        List<double[]> out = new ArrayList<>();
        if (wkt == null || !wkt.startsWith("LINESTRING(") || !wkt.endsWith(")")) {
            return out;
        }
        for (String pair : wkt.substring("LINESTRING(".length(), wkt.length() - 1).split(",")) {
            String[] xy = pair.trim().split("\\s+");
            if (xy.length < 2) {
                continue;
            }
            double lng = Math.round(Double.parseDouble(xy[0]) * 1e6) / 1e6;
            double lat = Math.round(Double.parseDouble(xy[1]) * 1e6) / 1e6;
            out.add(new double[] {lat, lng});
        }
        return out;
    }
}
