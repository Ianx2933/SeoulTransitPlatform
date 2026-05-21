package com.ian.transit.map.repository.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DemandSqlSupport}.
 */
class DemandSqlSupportTest {

    @Nested
    @DisplayName("getDayAggregationDivisor")
    class GetDayAggregationDivisor {

        @Test
        @DisplayName("returns 1 when aggregation is sum, regardless of day types")
        void returnsOneForSumAggregation() {
            assertEquals(
                    1,
                    DemandSqlSupport.getDayAggregationDivisor(
                            List.of("mon", "tue", "wed", "thu", "fri"),
                            "sum"
                    )
            );
        }

        @Test
        @DisplayName("returns the day type count when aggregation is average")
        void returnsDayTypeCountForAverage() {
            assertEquals(
                    5,
                    DemandSqlSupport.getDayAggregationDivisor(
                            List.of("mon", "tue", "wed", "thu", "fri"),
                            "average"
                    )
            );
        }

        @Test
        @DisplayName("returns 1 when day types are null even under average")
        void returnsOneWhenDayTypesNullUnderAverage() {
            assertEquals(
                    1,
                    DemandSqlSupport.getDayAggregationDivisor(null, "average")
            );
        }

        @Test
        @DisplayName("returns 1 when day types are empty even under average")
        void returnsOneWhenDayTypesEmptyUnderAverage() {
            assertEquals(
                    1,
                    DemandSqlSupport.getDayAggregationDivisor(List.of(), "average")
            );
        }

        @Test
        @DisplayName("handles a single-day average as divisor 1")
        void handlesSingleDayAverage() {
            assertEquals(
                    1,
                    DemandSqlSupport.getDayAggregationDivisor(List.of("mon"), "average")
            );
        }
    }

    @Nested
    @DisplayName("buildDemandAggregationExpression")
    class BuildDemandAggregationExpression {

        @Test
        @DisplayName("uses plain SUM when divisor is 1")
        void usesPlainSumWhenDivisorIsOne() {
            String expression = DemandSqlSupport.buildDemandAggregationExpression(
                    "d.boarding", 1
            );

            assertEquals("SUM(CAST(d.boarding AS BIGINT))", expression);
        }

        @Test
        @DisplayName("uses plain SUM when divisor is 0 or negative")
        void usesPlainSumWhenDivisorNonPositive() {
            assertEquals(
                    "SUM(CAST(d.boarding AS BIGINT))",
                    DemandSqlSupport.buildDemandAggregationExpression("d.boarding", 0)
            );
            assertEquals(
                    "SUM(CAST(d.boarding AS BIGINT))",
                    DemandSqlSupport.buildDemandAggregationExpression("d.boarding", -3)
            );
        }

        @Test
        @DisplayName("uses ROUND(SUM / divisor) for divisor greater than 1")
        void usesRoundedAverageForLargerDivisor() {
            String expression = DemandSqlSupport.buildDemandAggregationExpression(
                    "d.boarding", 5
            );

            assertEquals(
                    "ROUND(SUM(CAST(d.boarding AS NUMERIC)) / 5)",
                    expression
            );
        }

        @Test
        @DisplayName("respects the supplied column name")
        void respectsSuppliedColumnName() {
            String expression = DemandSqlSupport.buildDemandAggregationExpression(
                    "x.alighting", 3
            );

            assertTrue(
                    expression.contains("x.alighting"),
                    "expected column reference, got: " + expression
            );
        }
    }

    @Nested
    @DisplayName("appendInClause")
    class AppendInClause {

        @Test
        @DisplayName("appends nothing when values are null")
        void appendsNothingForNull() {
            StringBuilder sql = new StringBuilder("WHERE 1=1\n");
            List<Object> params = new ArrayList<>();

            DemandSqlSupport.appendInClause(sql, params, "d.day_type", null);

            assertEquals("WHERE 1=1\n", sql.toString());
            assertTrue(params.isEmpty());
        }

        @Test
        @DisplayName("appends nothing when values are empty")
        void appendsNothingForEmpty() {
            StringBuilder sql = new StringBuilder("WHERE 1=1\n");
            List<Object> params = new ArrayList<>();

            DemandSqlSupport.appendInClause(sql, params, "d.day_type", List.of());

            assertEquals("WHERE 1=1\n", sql.toString());
            assertTrue(params.isEmpty());
        }

        @Test
        @DisplayName("appends a single-element IN clause with one placeholder")
        void appendsSingleElementInClause() {
            StringBuilder sql = new StringBuilder("WHERE 1=1\n");
            List<Object> params = new ArrayList<>();

            DemandSqlSupport.appendInClause(sql, params, "d.day_type", List.of("mon"));

            assertEquals("WHERE 1=1\n  AND d.day_type IN (?)\n", sql.toString());
            assertEquals(List.of("mon"), params);
        }

        @Test
        @DisplayName("appends a multi-element IN clause with matching placeholders")
        void appendsMultiElementInClause() {
            StringBuilder sql = new StringBuilder("WHERE 1=1\n");
            List<Object> params = new ArrayList<>();

            DemandSqlSupport.appendInClause(
                    sql, params, "d.hour", List.of(7, 8, 9)
            );

            assertEquals(
                    "WHERE 1=1\n  AND d.hour IN (?, ?, ?)\n",
                    sql.toString()
            );
            assertEquals(List.of(7, 8, 9), params);
        }

        @Test
        @DisplayName("preserves previously added params and appends in order")
        void preservesExistingParams() {
            StringBuilder sql = new StringBuilder("WHERE d.mode = ?\n");
            List<Object> params = new ArrayList<>();
            params.add("subway");

            DemandSqlSupport.appendInClause(
                    sql, params, "d.day_type", List.of("mon", "tue")
            );

            assertEquals(List.of("subway", "mon", "tue"), params);
        }
    }
}
