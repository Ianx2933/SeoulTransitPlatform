package com.ian.transit.map.repository.support;

import java.util.List;

/**
 * Shared SQL helper methods for map demand repositories.
 */
public final class DemandSqlSupport {

    private DemandSqlSupport() {
    }

    /**
     * Returns the divisor used when selected day types should be averaged.
     */
    public static int getDayAggregationDivisor(List<String> dayTypes, String dayAggregation) {
        if (!"average".equals(dayAggregation)) {
            return 1;
        }

        if (dayTypes == null || dayTypes.isEmpty()) {
            return 1;
        }

        return dayTypes.size();
    }

    /**
     * Builds the SQL expression used for sum or average-per-selected-day display.
     */
    public static String buildDemandAggregationExpression(String columnName, int divisor) {
        if (divisor <= 1) {
            return "SUM(CAST(" + columnName + " AS BIGINT))";
        }

        return "ROUND(SUM(CAST(" + columnName + " AS NUMERIC)) / " + divisor + ")";
    }

    /**
     * Appends a parameterized IN clause.
     */
    public static void appendInClause(
            StringBuilder sql,
            List<Object> params,
            String columnName,
            List<?> values
    ) {
        if (values == null || values.isEmpty()) {
            return;
        }

        String placeholders = String.join(
                ", ",
                values.stream().map(value -> "?").toList()
        );

        sql.append("  AND ")
                .append(columnName)
                .append(" IN (")
                .append(placeholders)
                .append(")\n");

        params.addAll(values);
    }
}
