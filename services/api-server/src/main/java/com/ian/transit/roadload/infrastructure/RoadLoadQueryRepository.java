package com.ian.transit.roadload.infrastructure;

import com.ian.transit.dongod.application.RouteLoadCalculator;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Route OD legs for chosen routes or one stop, the routes that run over one
 * stop pair, and stop positions from the stored route paths.
 */
@Repository
@RequiredArgsConstructor
public class RoadLoadQueryRepository {

    private static final String LEGS_BY_ROUTES = """
            SELECT 노선명 AS route, 승차_정류장ars AS b, 하차_정류장ars AS a, SUM(승객수) AS p
            FROM od_typical_route_stop
            WHERE 기준일자 = :label AND 노선명 IN (:routes)
            GROUP BY 1, 2, 3
            """;

    /** %s is the stop column, 승차_정류장ars or 하차_정류장ars. */
    private static final String LEGS_BY_STOP = """
            SELECT 노선명 AS route, 승차_정류장ars AS b, 하차_정류장ars AS a, SUM(승객수) AS p
            FROM od_typical_route_stop
            WHERE 기준일자 = :label AND %s IN (:keys)
            GROUP BY 1, 2, 3
            """;

    private static final String ROUTES_THROUGH = """
            SELECT DISTINCT r.od_route_nm
            FROM bus_route_path_segment s
            JOIN bus_route_path_route r ON r.route_nm = s.route_nm
            WHERE r.status = 'ok' AND s.from_ars = :from AND s.to_ars = :to
            """;

    private static final String STOP_POINTS = """
            SELECT DISTINCT ON (ars) ars, station_nm, lat, lng
            FROM bus_route_path_station
            WHERE ars IN (:ars) AND lat IS NOT NULL AND lng IS NOT NULL
            ORDER BY ars, route_nm
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public record StopPoint(String ars, String name, double lat, double lng) {
    }

    private static RouteLoadCalculator.Leg leg(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new RouteLoadCalculator.Leg(rs.getString("route"), rs.getString("b"), rs.getString("a"), rs.getDouble("p"));
    }

    public List<RouteLoadCalculator.Leg> legsByRoutes(String label, Collection<String> routes) {
        if (routes.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("label", label)
                .addValue("routes", List.copyOf(routes));
        return jdbc.query(LEGS_BY_ROUTES, p, (rs, i) -> leg(rs));
    }

    /** Legs boarding (alighting = false) or alighting at one stop; ARS matched with and without zeros. */
    public List<RouteLoadCalculator.Leg> legsByStop(String label, String paddedArs, boolean alighting) {
        String unpadded = paddedArs.replaceFirst("^0+", "");
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("label", label)
                .addValue("keys", List.of(paddedArs, unpadded));
        String sql = LEGS_BY_STOP.formatted(alighting ? "하차_정류장ars" : "승차_정류장ars");
        return jdbc.query(sql, p, (rs, i) -> leg(rs));
    }

    /** TOPIS routes with a stored segment from one stop to the next. */
    public List<String> routesThrough(String fromArs, String toArs) {
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("from", fromArs).addValue("to", toArs);
        return jdbc.queryForList(ROUTES_THROUGH, p, String.class);
    }

    public Map<String, StopPoint> stopPoints(Collection<String> paddedArs) {
        Map<String, StopPoint> out = new HashMap<>();
        if (paddedArs.isEmpty()) {
            return out;
        }
        jdbc.query(STOP_POINTS, new MapSqlParameterSource("ars", List.copyOf(paddedArs)), rs -> {
            out.put(rs.getString("ars"), new StopPoint(rs.getString("ars"), rs.getString("station_nm"),
                    rs.getDouble("lat"), rs.getDouble("lng")));
        });
        return out;
    }
}
