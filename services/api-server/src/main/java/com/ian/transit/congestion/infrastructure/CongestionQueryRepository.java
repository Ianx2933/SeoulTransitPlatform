package com.ian.transit.congestion.infrastructure;

import com.ian.transit.congestion.model.CongestionRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Query repository for congestion-related SQL.
 * 
 * JdbcTemplate is used because congestion reconstruction relies on CTEs,
 * window functions, cumulative sums, and PostgreSQL-specific set operations.
 */
@Repository
@RequiredArgsConstructor
public class CongestionQueryRepository {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Finds congestion rows for a single route/date pair.
     * 
     * The query calculates occupancy using boarding as positive flow and alighting as negative flow.
     * 
     * 19 service hours is used as an operational approximation for average hourly in-vehicle load.
     * 
     * Negative occupancy can occutr because OD reconstruction and stop sequence correction are imperfect.
     */
    public List<CongestionRow> findRouteCongestion(String routeName, String date) {
        String sql = """
                WITH net_passenger AS (
                    -- Boarding passengers increase in-vehicle occupancy.
                    SELECT
                        노선명,
                        CAST(승차_정류장순번 AS INT) AS sequence,
                        승차_정류장ars AS ars,
                        승차_정류장명 AS stop_name,
                        SUM(CAST(승객수 AS INT)) AS net_passengers
                    FROM analysis_table_final
                    WHERE 노선명 = ?
                      AND 기준일자 = ?
                      AND 승차_정류장ars != '00000'
                    GROUP BY 노선명, 승차_정류장순번, 승차_정류장ars, 승차_정류장명

                    UNION ALL

                    -- Alighting passengers decrease in-vehicle occupancy.
                    SELECT
                        노선명,
                        CAST(하차_정류장순번 AS INT) AS sequence,
                        하차_정류장ars AS ars,
                        하차_정류장명 AS stop_name,
                        -SUM(CAST(승객수 AS INT)) AS net_passengers
                    FROM analysis_table_final
                    WHERE 노선명 = ?
                      AND 기준일자 = ?
                      AND 하차_정류장ars != '00000'
                    GROUP BY 노선명, 하차_정류장순번, 하차_정류장ars, 하차_정류장명
                ),
                stop_net_passenger AS (
                    -- Merge boarding/alighting flows at the same route-stop position.
                    SELECT
                        노선명,
                        sequence,
                        ars,
                        stop_name,
                        SUM(net_passengers) AS stop_net_passengers
                    FROM net_passenger
                    GROUP BY 노선명, sequence, ars, stop_name
                ),
                cumulative_occupancy AS (
                    -- Cumulative sum by stop sequence gives in-vehicle occupancy.
                    SELECT
                        노선명,
                        sequence,
                        ars,
                        stop_name,
                        SUM(stop_net_passengers) OVER (
                            PARTITION BY 노선명
                            ORDER BY sequence
                            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW
                        ) AS raw_occupancy
                    FROM stop_net_passenger
                ),
                normalized_occupancy AS (
                    -- Negative occupancy is corrected to 0, then normalized by 19 service hours.
                    SELECT
                        노선명,
                        sequence,
                        ars,
                        stop_name,
                        CASE
                            WHEN raw_occupancy < 0 THEN 0
                            ELSE ROUND(raw_occupancy / 19.0)
                        END AS occupancy
                    FROM cumulative_occupancy
                ),
                passenger_total AS (
                    -- Total passengers means boarding + alighting count at each ARS.
                    SELECT
                        노선명,
                        ars,
                        SUM(total_passengers) AS total_passengers
                    FROM (
                        SELECT
                            노선명,
                            승차_정류장ars AS ars,
                            SUM(CAST(승객수 AS INT)) AS total_passengers
                        FROM analysis_table_final
                        WHERE 노선명 = ?
                          AND 기준일자 = ?
                          AND 승차_정류장ars != '00000'
                        GROUP BY 노선명, 승차_정류장ars

                        UNION ALL

                        SELECT
                            노선명,
                            하차_정류장ars AS ars,
                            SUM(CAST(승객수 AS INT)) AS total_passengers
                        FROM analysis_table_final
                        WHERE 노선명 = ?
                          AND 기준일자 = ?
                          AND 하차_정류장ars != '00000'
                        GROUP BY 노선명, 하차_정류장ars
                    ) passenger_union
                    GROUP BY 노선명, ars
                ),
                stop_location AS (
                    -- Coordinates are joined by ARS + stop name to reduce duplicate ARS ambiguity.
                    SELECT
                        정류장번호 AS ars,
                        정류장명 AS stop_name,
                        위도 AS latitude,
                        경도 AS longitude
                    FROM bus_stop_location
                    WHERE 위도 IS NOT NULL
                      AND 경도 IS NOT NULL
                      AND 위도 BETWEEN 37.0 AND 38.5
                      AND 경도 BETWEEN 126.0 AND 128.0
                )
                SELECT
                    o.노선명 AS route_name,
                    o.sequence,
                    o.ars,
                    o.stop_name,
                    CAST(o.occupancy AS INT) AS occupancy,
                    CAST(MAX(o.occupancy) OVER (PARTITION BY o.노선명) AS INT) AS max_occupancy,
                    l.latitude,
                    l.longitude,
                    COALESCE(p.total_passengers, 0)::INT AS total_passengers
                FROM normalized_occupancy o
                LEFT JOIN stop_location l
                    ON l.ars = o.ars
                   AND l.stop_name = o.stop_name
                LEFT JOIN passenger_total p
                    ON p.노선명 = o.노선명
                   AND p.ars = o.ars
                ORDER BY o.sequence
                """;

        return jdbcTemplate.query(
                sql,
                this::mapCongestionRow,
                routeName,
                date,
                routeName,
                date,
                routeName,
                date,
                routeName,
                date
        );
    }

    /**
     * Finds ordered stop names for one route/date pair.
     * 
     * Using ARS code alone may be ambiguous in legacy or duplicated stop datasets.
     */
    public List<String> findStopsByRoute(String routeName, String date) {
        String sql = """
                SELECT 승차_정류장명 AS stop_name
                FROM analysis_table_final
                WHERE 노선명 = ?
                  AND 기준일자 = ?
                  AND 승차_정류장ars != '00000'
                GROUP BY 승차_정류장명, 승차_정류장순번
                ORDER BY MIN(CAST(승차_정류장순번 AS INT)) ASC
                """;

        return jdbcTemplate.queryForList(sql, String.class, routeName, date);
    }

    /**
     * Maps ResultSet row to internal query model.
     */
    private CongestionRow mapCongestionRow(ResultSet rs, int rowNum) throws SQLException {
        return new CongestionRow(
                rs.getString("route_name"),
                rs.getInt("sequence"),
                rs.getString("ars"),
                rs.getString("stop_name"),
                rs.getInt("occupancy"),
                rs.getInt("max_occupancy"),
                getNullableDouble(rs, "latitude"),
                getNullableDouble(rs, "longitude"),
                rs.getInt("total_passengers")
        );
    }

    /**
     * Reads nullable double safely from ResultSet.
     */
    private Double getNullableDouble(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : value;
    }
}
