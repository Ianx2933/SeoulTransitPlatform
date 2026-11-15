package com.ian.transit.curatedod.infrastructure;

import com.ian.transit.curatedod.application.RouteOdAnalyzer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;

/**
 * Reads one hour of one route from estimated_hourly_od.
 *
 * The hourly table splits each typical-day OD pair over the 24 hours by the
 * boarding stop's hourly profile, so {@code hour} is the hour the riders
 * boarded. Its 기준일자 uses the same labels as od_typical_route_stop
 * (typical_weekday, ...). Only weekdays are loaded at present, so other labels
 * return nothing. The key includes method; a pair has one method, but the
 * rows are summed so a stray second method cannot drop passengers.
 */
@Repository
@RequiredArgsConstructor
public class HourlyOdQueryRepository {

    private static final String SQL = """
            SELECT 승차_정류장ars AS board_ars, 하차_정류장ars AS alight_ars,
                   SUM(estimated_passengers) AS passengers
            FROM estimated_hourly_od
            WHERE 기준일자 = :label AND 노선명 = :route AND hour = :hour
            GROUP BY 승차_정류장ars, 하차_정류장ars
            """;

    private final NamedParameterJdbcTemplate jdbc;

    /** Passengers by OD pair (keyed by {@link RouteOdAnalyzer#pairKey}); empty when there are none. */
    public Map<String, Double> passengersByPair(String label, String route, int hour) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("label", label)
                .addValue("route", route)
                .addValue("hour", hour);
        Map<String, Double> out = new HashMap<>();
        jdbc.query(SQL, params, rs -> {
            out.merge(RouteOdAnalyzer.pairKey(rs.getString("board_ars"), rs.getString("alight_ars")),
                    rs.getDouble("passengers"), Double::sum);
        });
        return out;
    }
}
