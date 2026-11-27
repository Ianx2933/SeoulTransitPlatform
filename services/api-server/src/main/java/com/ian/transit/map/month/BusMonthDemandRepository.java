package com.ian.transit.map.month;

import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryParameterValue;
import com.ian.transit.period.infrastructure.BigQueryRunner;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bus demand of one month, by day type, for the demand map.
 *
 * The source has one total per month, route, stop and hour
 * (transit_marts.fct_boarding). It is split into day types the way the
 * typical map figures were solved: dow_boarding_shape holds, per route, stop
 * and hour, the mean boardings of each day type over every stored month
 * (solve_dow_hourly_ratio's least squares solution x_d), and month_day_count
 * the number of days of each type in the month (n_d). Assuming the month keeps
 * the stop's day-type shape, a day of type d in the month carries
 *
 *     month total × x_d / Σ n_d x_d
 *
 * and a key without a shape gets the month total / days in the month.
 * Alightings take the boardings' shape (the source has no alighting split).
 * fct_boarding is partitioned by month, so a query reads one month.
 */
@Repository
public class BusMonthDemandRepository {

    public record StopMonthDemand(String route, String ars, double boarding, double alighting) {
    }

    public record StopPoint(String ars, String name, double lat, double lng) {
    }

    private static final String STOP_POINTS = """
            SELECT DISTINCT ON (ars) ars, name, lat, lng
            FROM (
                SELECT LPAD(b."정류장번호"::text, 5, '0') AS ars, b."정류장명" AS name,
                       b."위도" AS lat, b."경도" AS lng, 0 AS pri
                FROM bus_stop_location b
                WHERE LPAD(b."정류장번호"::text, 5, '0') IN (:ars)
                  AND b."위도" IS NOT NULL AND b."경도" IS NOT NULL
                UNION ALL
                SELECT LPAD(i.ars_id::text, 5, '0'), i.stop_name, i.lat, i.lng, 1
                FROM integrated_bus_stop_location i
                WHERE LPAD(i.ars_id::text, 5, '0') IN (:ars)
                  AND i.lat IS NOT NULL AND i.lng IS NOT NULL
            ) x
            ORDER BY ars, pri
            """;

    /**
     * Route stops away from the Seoul stop of their number
     * (13_stop_ars_route_away), "route|padded ARS" → own position. Empty
     * until the view exists.
     */
    public Map<String, StopPoint> awayPoints() {
        Map<String, StopPoint> out = new HashMap<>();
        if (jdbc == null) {
            return out;
        }
        try {
            jdbc.query("SELECT route_nm, ars, node_nm, lat, lng FROM stop_ars_route_away", rs -> {
                out.put(rs.getString("route_nm") + "|" + rs.getString("ars"), new StopPoint(rs.getString("ars"),
                        rs.getString("node_nm"), rs.getDouble("lat"), rs.getDouble("lng")));
            });
        } catch (org.springframework.jdbc.BadSqlGrammarException e) {
            out.clear();
        }
        return out;
    }

    private final BigQueryRunner runner;
    private final NamedParameterJdbcTemplate jdbc;

    public BusMonthDemandRepository(BigQueryRunner runner, NamedParameterJdbcTemplate jdbc) {
        this.runner = runner;
        this.jdbc = jdbc;
    }

    static String monthSql(String fct, String shape, String days) {
        return """
                WITH m AS (
                  SELECT route_no, LPAD(stop_key, 5, '0') AS ars, hour,
                         SUM(boardings) AS b, SUM(alightings) AS a
                  FROM %1$s
                  WHERE service_ym = @ym
                    AND route_no IN UNNEST(@routes)
                    AND hour IN UNNEST(@hours)
                    AND REGEXP_CONTAINS(stop_key, r'^[0-9]{1,5}$')
                  GROUP BY 1, 2, 3
                ),
                c AS (
                  SELECT mon, tue, wed, thu, fri, sat, sun_holiday,
                         mon + tue + wed + thu + fri + sat + sun_holiday AS n
                  FROM %3$s
                  WHERE service_ym = @ym
                ),
                s AS (
                  SELECT route_no, stop_key AS ars, hour, day_type, avg_boardings AS v
                  FROM %2$s
                  WHERE route_no IN UNNEST(@routes) AND hour IN UNNEST(@hours)
                ),
                d AS (
                  SELECT s.route_no, s.ars, s.hour,
                         SUM(s.v * CASE s.day_type
                               WHEN 'mon' THEN c.mon WHEN 'tue' THEN c.tue WHEN 'wed' THEN c.wed
                               WHEN 'thu' THEN c.thu WHEN 'fri' THEN c.fri WHEN 'sat' THEN c.sat
                               WHEN 'sun_holiday' THEN c.sun_holiday END) AS denom
                  FROM s CROSS JOIN c
                  GROUP BY 1, 2, 3
                ),
                t AS (SELECT day_type FROM UNNEST(@day_types) AS day_type)
                SELECT m.route_no, m.ars,
                       SUM(IF(d.denom > 0, m.b * IFNULL(sv.v, 0) / d.denom, m.b / c.n)) AS boarding,
                       SUM(IF(d.denom > 0, m.a * IFNULL(sv.v, 0) / d.denom, m.a / c.n)) AS alighting
                FROM m
                CROSS JOIN c
                CROSS JOIN t
                LEFT JOIN d ON d.route_no = m.route_no AND d.ars = m.ars AND d.hour = m.hour
                LEFT JOIN s AS sv ON sv.route_no = m.route_no AND sv.ars = m.ars AND sv.hour = m.hour
                                 AND sv.day_type = t.day_type
                GROUP BY 1, 2
                """.formatted(fct, shape, days);
    }

    /** Summed over the hours and the day types given (the caller divides for a mean). */
    public List<StopMonthDemand> demand(int serviceYm, Collection<String> dayTypes, Collection<Integer> hours,
                                        Collection<String> routes) {
        var props = runner.props();
        String sql = monthSql(props.table("fct_boarding"), props.table("dow_boarding_shape"),
                props.table("month_day_count"));
        Map<String, QueryParameterValue> p = new LinkedHashMap<>();
        p.put("ym", QueryParameterValue.int64(serviceYm));
        p.put("routes", BigQueryRunner.strings(routes));
        p.put("hours", QueryParameterValue.array(hours.stream().map(Integer::longValue).toArray(Long[]::new), Long.class));
        p.put("day_types", BigQueryRunner.strings(dayTypes));
        return runner.query("bus_month_demand", sql, p, BusMonthDemandRepository::row);
    }

    private static StopMonthDemand row(FieldValueList r) {
        return new StopMonthDemand(r.get("route_no").getStringValue(), r.get("ars").getStringValue(),
                r.get("boarding").isNull() ? 0 : r.get("boarding").getDoubleValue(),
                r.get("alighting").isNull() ? 0 : r.get("alighting").getDoubleValue());
    }

    /** Months with bus boardings and day counts, as YYYYMM. */
    public List<Integer> months() {
        String sql = "SELECT service_ym FROM %s ORDER BY service_ym".formatted(runner.props().table("month_day_count"));
        return runner.query("bus_months", sql, Map.of(), r -> (int) r.get("service_ym").getLongValue());
    }

    /** Positions of the stops: the Seoul stop master first, then the metropolitan stop table. */
    public Map<String, StopPoint> stopPoints(Collection<String> paddedArs) {
        Map<String, StopPoint> out = new HashMap<>();
        if (paddedArs.isEmpty()) {
            return out;
        }
        List<String> keys = new ArrayList<>(paddedArs);
        jdbc.query(STOP_POINTS, new MapSqlParameterSource("ars", keys), rs -> {
            out.put(rs.getString("ars"), new StopPoint(rs.getString("ars"), rs.getString("name"),
                    rs.getDouble("lat"), rs.getDouble("lng")));
        });
        return out;
    }
}
