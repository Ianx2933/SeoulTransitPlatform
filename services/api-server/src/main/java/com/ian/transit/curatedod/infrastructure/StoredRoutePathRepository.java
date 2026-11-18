package com.ian.transit.curatedod.infrastructure;

import com.ian.transit.dongod.infrastructure.RouteLoadQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Route paths stored by pipelines/bus_route_path: stations of every stored
 * variant of a TOPIS route, and one variant's segments in order.
 */
@Repository
@RequiredArgsConstructor
public class StoredRoutePathRepository {

    private static final String STATIONS = """
            SELECT r.route_nm AS variant, r.route_id, s.seq, s.ars, s.station_nm, s.lat, s.lng, s.trans_yn
            FROM bus_route_path_station s
            JOIN bus_route_path_route r ON r.route_nm = s.route_nm
            WHERE r.status = 'ok' AND r.od_route_nm = :route
              AND s.lat IS NOT NULL AND s.lng IS NOT NULL
            ORDER BY r.route_nm, s.seq
            """;

    private static final String SEGMENTS = """
            SELECT ST_AsText(geom) AS wkt
            FROM bus_route_path_segment
            WHERE route_nm = :variant
            ORDER BY seq
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record StationRow(String variant, String routeId, int seq, String ars, String name,
                             double lat, double lng, boolean turnaround) {
    }

    public List<StationRow> stations(String odRouteName) {
        return jdbc.query(STATIONS, new MapSqlParameterSource("route", odRouteName), (rs, i) -> new StationRow(
                rs.getString("variant"), rs.getString("route_id"), rs.getInt("seq"), rs.getString("ars"),
                rs.getString("station_nm"), rs.getDouble("lat"), rs.getDouble("lng"), rs.getBoolean("trans_yn")));
    }

    /** [lat, lng] lines of a variant's segments, in stop order. */
    public List<List<double[]>> segmentLines(String variant) {
        return jdbc.query(SEGMENTS, new MapSqlParameterSource("variant", variant),
                (rs, i) -> RouteLoadQueryRepository.parseLineString(rs.getString("wkt")));
    }
}
