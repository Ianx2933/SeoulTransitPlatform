package com.ian.transit.scenario.infrastructure;

import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Stored route stations at given stops, and the stored road path between two stops. */
@Repository
@RequiredArgsConstructor
public class ScenarioQueryRepository {

    private static final String STATIONS_AT = """
            SELECT r.od_route_nm, s.route_nm, s.seq, s.ars
            FROM bus_route_path_station s
            JOIN bus_route_path_route r ON r.route_nm = s.route_nm
            WHERE r.status = 'ok' AND s.ars IN (:ars)
            """;

    /** The shortest stored segment for each stop pair (several routes may run it). */
    private static final String PAIR_SEGMENTS = """
            SELECT DISTINCT ON (s.from_ars, s.to_ars) s.from_ars, s.to_ars, s.length_m,
                   ST_AsText(ST_SimplifyPreserveTopology(s.geom, 0.00005)) AS wkt
            FROM bus_route_path_segment s
            WHERE s.from_ars IN (:ars) AND s.to_ars IN (:ars)
            ORDER BY s.from_ars, s.to_ars, s.length_m
            """;

    /** As PAIR_SEGMENTS, also through earlier paths of changed routes (bus_route_path_version_segment). */
    private static final String PAIR_SEGMENTS_WITH_HISTORY = """
            SELECT DISTINCT ON (s.from_ars, s.to_ars) s.from_ars, s.to_ars, s.length_m,
                   ST_AsText(ST_SimplifyPreserveTopology(s.geom, 0.00005)) AS wkt
            FROM (
                SELECT from_ars, to_ars, length_m, geom FROM bus_route_path_segment
                WHERE from_ars IN (:ars) AND to_ars IN (:ars)
                UNION ALL
                SELECT from_ars, to_ars, length_m, geom FROM bus_route_path_version_segment
                WHERE from_ars IN (:ars) AND to_ars IN (:ars)
            ) s
            ORDER BY s.from_ars, s.to_ars, s.length_m
            """;

    /**
     * Stop positions as the Seoul bus route API gives them on stored paths,
     * current and earlier. Covers stops outside Seoul that a Seoul route serves
     * (Gyeonggi stops the stop tables lack).
     */
    private static final String STATION_POINTS = """
            SELECT DISTINCT ON (ars) ars, station_nm, lat, lng
            FROM (
                SELECT ars, station_nm, lat, lng, 0 AS age FROM bus_route_path_station
                WHERE ars IN (:ars) AND lat IS NOT NULL AND lng IS NOT NULL
                UNION ALL
                SELECT ars, station_nm, lat, lng, 1 AS age FROM bus_route_path_version_station
                WHERE ars IN (:ars) AND lat IS NOT NULL AND lng IS NOT NULL
            ) s
            ORDER BY ars, age
            """;

    private static final String STATION_POINTS_CURRENT = """
            SELECT DISTINCT ON (ars) ars, station_nm, lat, lng FROM bus_route_path_station
            WHERE ars IN (:ars) AND lat IS NOT NULL AND lng IS NOT NULL
            ORDER BY ars
            """;

    public record StationPoint(String ars, String name, double lat, double lng) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    /** Key: padded ARS. Current paths only while migration 03 is not applied. */
    public Map<String, StationPoint> stationPoints(Collection<String> paddedArs) {
        Map<String, StationPoint> out = new HashMap<>();
        if (paddedArs.isEmpty()) {
            return out;
        }
        var params = new MapSqlParameterSource("ars", List.copyOf(paddedArs));
        RowCallbackHandler put = rs -> out.put(rs.getString("ars"),
                new StationPoint(rs.getString("ars"), rs.getString("station_nm"), rs.getDouble("lat"), rs.getDouble("lng")));
        try {
            jdbc.query(STATION_POINTS, params, put);
        } catch (BadSqlGrammarException e) {
            out.clear();
            jdbc.query(STATION_POINTS_CURRENT, params, put);
        }
        return out;
    }

    /** A stored station: TOPIS route name, stored variant, BIS sequence, ARS. */
    public record StationAt(String odRoute, String variant, int seq, String ars) {
    }

    public record PairSegment(String fromArs, String toArs, double lengthM, List<double[]> path) {
    }

    public List<StationAt> stationsAt(Collection<String> paddedArs) {
        if (paddedArs.isEmpty()) {
            return List.of();
        }
        return jdbc.query(STATIONS_AT, new MapSqlParameterSource("ars", List.copyOf(paddedArs)),
                (rs, i) -> new StationAt(rs.getString("od_route_nm"), rs.getString("route_nm"),
                        rs.getInt("seq"), rs.getString("ars")));
    }

    /** Key "from>to". */
    public Map<String, PairSegment> pairSegments(Collection<String> paddedArs) {
        return pairs(PAIR_SEGMENTS, paddedArs);
    }

    /**
     * Pair segments of current and earlier stored paths, for a route as it ran
     * in a past period. Current paths only while migration 03 is not applied.
     */
    public Map<String, PairSegment> pairSegmentsWithHistory(Collection<String> paddedArs) {
        try {
            return pairs(PAIR_SEGMENTS_WITH_HISTORY, paddedArs);
        } catch (BadSqlGrammarException e) {
            return pairs(PAIR_SEGMENTS, paddedArs);
        }
    }

    private Map<String, PairSegment> pairs(String sql, Collection<String> paddedArs) {
        Map<String, PairSegment> out = new HashMap<>();
        if (paddedArs.isEmpty()) {
            return out;
        }
        jdbc.query(sql, new MapSqlParameterSource("ars", List.copyOf(paddedArs)), rs -> {
            var seg = new PairSegment(rs.getString("from_ars"), rs.getString("to_ars"), rs.getDouble("length_m"),
                    new ArrayList<>(RouteLoadQueryRepository.parseLineString(rs.getString("wkt"))));
            out.put(seg.fromArs() + ">" + seg.toArs(), seg);
        });
        return out;
    }
}
