package com.ian.transit.period.infrastructure;

import com.google.cloud.bigquery.FieldValueList;
import com.google.cloud.bigquery.QueryParameterValue;
import com.ian.transit.period.BigQueryProperties;
import com.ian.transit.period.Period;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Route stop OD of a period and the boarding-hour profiles, from transit-bigquery's
 * marts (transit_marts):
 *   od_period_days   route-OD days per mode and month (od_typical_days without its window)
 *   fct_od_stop      route stop OD per day, partitioned by day, clustered mode/route/board stop
 *   hourly_profile   24-hour boarding profile per route, stop and day type
 *
 * A period's OD follows fct_od_typical: per pair, riders summed over the period's
 * days of the day type and divided by the number of those days (a pair absent on
 * a day counts as 0). Sequences come from the latest of those days on which the
 * pair occurred, as od_typical_route_stop_serving takes them.
 *
 * A row whose boarding or alighting stop has neither an ARS nor a name cannot
 * be keyed (Cloud SQL's od_typical_route_stop requires both keys), so it is left
 * out, as it is out of the typical day; 143's 2026-03..05 had such a row (2026-11-20).
 */
@Repository
public class PeriodOdRepository {

    /** A period leg in the shape both route analysis and the road views use. */
    public record PeriodLeg(String route, Integer boardSeq, String boardArs, String boardName,
                            Integer alightSeq, String alightArs, String alightName, double passengers) {
    }

    public record PeriodDay(LocalDate date, String mode) {
    }

    /** Only these three filters exist; the SQL is never built from request text. */
    private enum Filter {
        ROUTES("f.route_name IN UNNEST(@keys)"),
        BOARDING("f.board_stop_key IN UNNEST(@keys)"),
        ALIGHTING("f.alight_stop_key IN UNNEST(@keys)");

        final String sql;

        Filter(String sql) {
            this.sql = sql;
        }
    }

    private final BigQueryRunner runner;

    public PeriodOdRepository(BigQueryRunner runner) {
        this.runner = runner;
    }

    private BigQueryProperties props() {
        return runner.props();
    }

    private static Map<String, QueryParameterValue> periodParams(Period period, String dayType) {
        Map<String, QueryParameterValue> p = new LinkedHashMap<>();
        p.put("day_type", QueryParameterValue.string(dayType));
        p.put("first_month", QueryParameterValue.date(period.firstMonthDay().toString()));
        p.put("last_month", QueryParameterValue.date(period.lastMonthDay().toString()));
        p.put("last_day", QueryParameterValue.date(period.lastDay().toString()));
        return p;
    }

    /** The days a period averages over, per mode (bus, ferry). */
    public List<PeriodDay> days(Period period, String dayType) {
        String sql = """
                SELECT service_date, mode
                FROM %s
                WHERE day_type = @day_type AND month BETWEEN @first_month AND @last_month
                ORDER BY service_date, mode
                """.formatted(props().table("od_period_days"));
        return runner.query("period_days", sql, periodParams(period, dayType),
                r -> new PeriodDay(LocalDate.parse(r.get("service_date").getStringValue()), r.get("mode").getStringValue()));
    }

    /** First and last month that have route OD, e.g. [2023-10-01, 2026-09-01]. */
    public List<LocalDate> monthRange() {
        String sql = "SELECT MIN(month) AS first_month, MAX(month) AS last_month FROM %s"
                .formatted(props().table("od_period_days"));
        List<List<LocalDate>> rows = runner.query("period_range", sql, Map.of(), r -> List.of(
                LocalDate.parse(r.get("first_month").getStringValue()),
                LocalDate.parse(r.get("last_month").getStringValue())));
        return rows.isEmpty() ? List.of() : rows.get(0);
    }

    public List<PeriodLeg> legsByRoutes(Period period, String dayType, Collection<String> routes) {
        return legs("legs_by_routes", Filter.ROUTES, period, dayType, routes);
    }

    /** Legs boarding (alighting = false) or alighting at the stops; keys in every padding the data uses. */
    public List<PeriodLeg> legsByStops(Period period, String dayType, Collection<String> keys, boolean alighting) {
        return legs(alighting ? "legs_alighting" : "legs_boarding",
                alighting ? Filter.ALIGHTING : Filter.BOARDING, period, dayType, keys);
    }

    static String legsSql(BigQueryProperties props, String filter) {
        return """
                WITH days AS (
                  SELECT service_date, mode
                  FROM %1$s
                  WHERE day_type = @day_type AND month BETWEEN @first_month AND @last_month
                ),
                n AS (SELECT mode, COUNT(*) AS n_days FROM days GROUP BY mode),
                od_rows AS (
                  SELECT f.mode, f.route_name,
                         COALESCE(f.board_stop_key, CONCAT('name:', f.board_name))   AS board_key,
                         COALESCE(f.alight_stop_key, CONCAT('name:', f.alight_name)) AS alight_key,
                         f.board_name, f.alight_name, f.board_seq, f.alight_seq,
                         f.service_date, f.passengers
                  FROM %2$s f
                  JOIN days d USING (service_date, mode)
                  WHERE f.service_date BETWEEN @first_month AND @last_day
                    AND f.route_name IS NOT NULL
                    AND COALESCE(f.board_stop_key, f.board_name) IS NOT NULL
                    AND COALESCE(f.alight_stop_key, f.alight_name) IS NOT NULL
                    AND %3$s
                )
                SELECT r.route_name, r.board_key, r.alight_key,
                       ANY_VALUE(r.board_name) AS board_name,
                       ANY_VALUE(r.alight_name) AS alight_name,
                       ARRAY_AGG(STRUCT(r.board_seq AS bq, r.alight_seq AS aq)
                                 ORDER BY r.service_date DESC LIMIT 1)[OFFSET(0)] AS s,
                       SUM(r.passengers) / ANY_VALUE(n.n_days) AS passengers
                FROM od_rows r
                JOIN n USING (mode)
                GROUP BY 1, 2, 3
                """.formatted(props.table("od_period_days"), props.table("fct_od_stop"), filter);
    }

    private List<PeriodLeg> legs(String what, Filter filter, Period period, String dayType, Collection<String> keys) {
        if (keys.isEmpty()) {
            return List.of();
        }
        Map<String, QueryParameterValue> p = periodParams(period, dayType);
        p.put("keys", BigQueryRunner.strings(keys));
        return runner.query(what, legsSql(props(), filter.sql), p, PeriodOdRepository::leg);
    }

    private static PeriodLeg leg(FieldValueList r) {
        FieldValueList s = r.get("s").getRecordValue();
        return new PeriodLeg(r.get("route_name").getStringValue(),
                intOrNull(s, 0), r.get("board_key").getStringValue(), stringOrNull(r, "board_name"),
                intOrNull(s, 1), r.get("alight_key").getStringValue(), stringOrNull(r, "alight_name"),
                r.get("passengers").getDoubleValue());
    }

    private static Integer intOrNull(FieldValueList record, int index) {
        return record.get(index).isNull() ? null : (int) record.get(index).getLongValue();
    }

    private static String stringOrNull(FieldValueList r, String name) {
        return r.get(name).isNull() ? null : r.get(name).getStringValue();
    }

    /** 24-hour boarding profiles of the routes, keyed route|stop_key. */
    public Map<String, double[]> profiles(String dayType, Collection<String> routes) {
        Map<String, double[]> out = new LinkedHashMap<>();
        if (routes.isEmpty()) {
            return out;
        }
        String sql = """
                SELECT route_no, stop_key, hour, ratio
                FROM %s
                WHERE day_type = @day_type AND route_no IN UNNEST(@routes)
                """.formatted(props().table("hourly_profile"));
        Map<String, QueryParameterValue> p = new LinkedHashMap<>();
        p.put("day_type", QueryParameterValue.string(dayType));
        p.put("routes", BigQueryRunner.strings(routes));
        runner.query("hourly_profiles", sql, p, r -> {
            String key = r.get("route_no").getStringValue() + "|" + r.get("stop_key").getStringValue();
            out.computeIfAbsent(key, k -> new double[24])[(int) r.get("hour").getLongValue()] =
                    r.get("ratio").getDoubleValue();
            return Boolean.TRUE;
        });
        return out;
    }

    /** The fallback profile (default_hourly_ratio, normalised to sum to one). */
    public double[] defaultProfile() {
        String sql = "SELECT hour, raw_ratio FROM %s".formatted(props().table("default_hourly_ratio"));
        double[] raw = new double[24];
        runner.query("default_profile", sql, Map.of(), r -> {
            raw[(int) r.get("hour").getLongValue()] = r.get("raw_ratio").getDoubleValue();
            return Boolean.TRUE;
        });
        double sum = 0;
        for (double v : raw) {
            sum += v;
        }
        if (sum <= 0) {
            throw new IllegalStateException("default_hourly_ratio is empty");
        }
        for (int h = 0; h < 24; h++) {
            raw[h] /= sum;
        }
        return raw;
    }
}
