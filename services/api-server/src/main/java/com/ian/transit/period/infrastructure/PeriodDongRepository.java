package com.ian.transit.period.infrastructure;

import com.google.cloud.bigquery.QueryParameterValue;
import com.ian.transit.dongod.application.DongArea;
import com.ian.transit.period.BigQueryProperties;
import com.ian.transit.period.Period;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * District OD of a period from transit-bigquery's fct_od_dong_month (sums per
 * month over the period days of each mode) and od_dong_period_days (the day
 * counts). A pair's mean is its sum over the chosen months divided by the
 * days of each mode, the rule of fct_od_dong_mode_typical, so a period equal
 * to the typical window gives the typical numbers
 * (assert_fct_od_dong_month_typical_matches).
 */
@Repository
public class PeriodDongRepository {

    /** One averaged pair, in the shape of a Cloud SQL od_dong_typical row. */
    public record Pair(String originCd, String originName, String destCd, String destName,
                       double subway, double bus) {
        public double total() {
            return subway + bus;
        }
    }

    /** Which end of a pair must lie in the area. */
    public enum End {
        ORIGIN, DEST, EITHER, ANY
    }

    private final BigQueryRunner runner;

    public PeriodDongRepository(BigQueryRunner runner) {
        this.runner = runner;
    }

    /** BigQuery condition that a code column lies in the area; fixed text per level. */
    static String inArea(DongArea level, String column) {
        return switch (level) {
            case DONG -> column + " = @code";
            case GU -> "STARTS_WITH(" + column + ", @code)";
            case CITY -> column + " != 'outside'";
            case GROUP -> column + " IN UNNEST(@codes)";
        };
    }

    static String where(DongArea level, End end) {
        return switch (end) {
            case ORIGIN -> inArea(level, "m.origin_cd");
            case DEST -> inArea(level, "m.dest_cd");
            case EITHER -> "(" + inArea(level, "m.origin_cd") + " OR " + inArea(level, "m.dest_cd") + ")";
            case ANY -> "TRUE";
        };
    }

    public List<Pair> pairs(Period period, String dayType, DongArea level, String code, End end) {
        BigQueryProperties props = runner.props();
        // One TOPIS day: that day's district OD as published (fct_od_dong_daily,
        // one partition), not the special-day rules of a period mean.
        String daySql = """
                SELECT m.origin_cd, ANY_VALUE(m.origin_name) AS origin_name,
                       m.dest_cd, ANY_VALUE(m.dest_name) AS dest_name,
                       SUM(m.subway_rides) AS subway,
                       SUM(m.bus_rides) AS bus
                FROM %1$s m
                WHERE m.service_date = @day
                  AND %2$s
                GROUP BY m.origin_cd, m.dest_cd
                """.formatted(props.table("fct_od_dong_daily"), where(level, end));
        String sql = period.oneDay() ? daySql : """
                WITH n AS (
                  SELECT COUNTIF(mode = 'subway') AS ns, COUNTIF(mode = 'bus') AS nb
                  FROM %1$s
                  WHERE day_type = @day_type AND month BETWEEN @first_month AND @last_month
                )
                SELECT m.origin_cd, ANY_VALUE(m.origin_name) AS origin_name,
                       m.dest_cd, ANY_VALUE(m.dest_name) AS dest_name,
                       SAFE_DIVIDE(SUM(m.subway_rides), ANY_VALUE(n.ns)) AS subway,
                       SAFE_DIVIDE(SUM(m.bus_rides), ANY_VALUE(n.nb)) AS bus
                FROM %2$s m
                CROSS JOIN n
                WHERE m.month BETWEEN @first_month AND @last_month
                  AND m.day_type = @day_type
                  AND %3$s
                GROUP BY m.origin_cd, m.dest_cd
                """.formatted(props.table("od_dong_period_days"), props.table("fct_od_dong_month"), where(level, end));
        Map<String, QueryParameterValue> p = new LinkedHashMap<>();
        p.put("day_type", QueryParameterValue.string(dayType));
        p.put("first_month", QueryParameterValue.date(period.firstMonthDay().toString()));
        p.put("last_month", QueryParameterValue.date(period.lastMonthDay().toString()));
        if (period.oneDay()) {
            p.clear();
            p.put("day", QueryParameterValue.date(period.day().toString()));
        }
        if (end != End.ANY) {
            if (level == DongArea.GROUP) {
                p.put("codes", BigQueryRunner.strings(DongArea.groupCodes(code)));
            } else if (level != DongArea.CITY) {
                p.put("code", QueryParameterValue.string(code));
            }
        }
        return runner.query("dong_pairs_" + end.name().toLowerCase(), sql, p, r -> new Pair(
                r.get("origin_cd").getStringValue(),
                r.get("origin_name").isNull() ? null : r.get("origin_name").getStringValue(),
                r.get("dest_cd").getStringValue(),
                r.get("dest_name").isNull() ? null : r.get("dest_name").getStringValue(),
                r.get("subway").isNull() ? 0 : r.get("subway").getDoubleValue(),
                r.get("bus").isNull() ? 0 : r.get("bus").getDoubleValue()));
    }
}
