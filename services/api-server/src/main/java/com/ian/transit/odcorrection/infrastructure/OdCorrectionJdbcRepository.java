package com.ian.transit.odcorrection.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-heavy repository for long SQL and temporarytable operations.
 * 
 * JdbcTemplate is used here because deduplication and bulk correction require
 * PostgreSQL-specific SQL, temporary tables, and set-based operations.
 */

@Repository
@RequiredArgsConstructor
public class OdCorrectionJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

/**
 * Aggregates duplicated OD rows after correction while preserving total passengers.
 *
 * A temporary table is used to avoid row-by-row updates and keep the operation set based in PostgreSQL.
 */

    public int deduplicateAndSum(String 기준일자) {
        Integer duplicateCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM (" +
            "    SELECT 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장명 " +
            "    FROM analysis_table_final WHERE 기준일자 = ? " +
            "    GROUP BY 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장명 " +
            "    HAVING COUNT(*) > 1" +
            ") t",
            Integer.class,
            기준일자
        );

        if (duplicateCount == null || duplicateCount == 0) {
            return 0;
        }

        jdbcTemplate.execute("DROP TABLE IF EXISTS 중복합산임시");

        jdbcTemplate.execute(
            "CREATE TEMP TABLE 중복합산임시 AS " +
            "SELECT MIN(기준일자) AS 기준일자, 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, SUM(승객수) AS 승객수 " +
            "FROM analysis_table_final WHERE 기준일자 = '" + 기준일자 + "' " +
            "GROUP BY 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명 " +
            "HAVING COUNT(*) > 1"
        );

        jdbcTemplate.update(
            "DELETE FROM analysis_table_final a USING 중복합산임시 b " +
            "WHERE a.기준일자 = b.기준일자 " +
            "AND a.노선명 = b.노선명 " +
            "AND a.전환_노선id = b.전환_노선id " +
            "AND a.승차_정류장순번 = b.승차_정류장순번 " +
            "AND a.승차_정류장ars = b.승차_정류장ars " +
            "AND a.하차_정류장순번 = b.하차_정류장순번 " +
            "AND a.하차_정류장ars = b.하차_정류장ars"
        );

        jdbcTemplate.update(
            "INSERT INTO analysis_table_final " +
            "(기준일자, 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, 승객수) " +
            "SELECT 기준일자, 노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, 하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명, 승객수 FROM 중복합산임시"
        );

        jdbcTemplate.execute("DROP TABLE IF EXISTS 중복합산임시");

        return duplicateCount;
    }

    public int fixAlightingSequenceByArs(String 기준일자) {
        return jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 하차_정류장순번 = b.최소하차순번 " +
            "FROM (SELECT 노선명, 승차_정류장순번, 승차_정류장ars, 하차_정류장ars, MIN(하차_정류장순번) AS 최소하차순번 " +
            "FROM analysis_table_final WHERE 승차_정류장ars <> '00000' AND 하차_정류장ars <> '00000' AND 기준일자 = ? " +
            "GROUP BY 노선명, 승차_정류장순번, 승차_정류장ars, 하차_정류장ars " +
            "HAVING COUNT(DISTINCT 하차_정류장순번) > 1 AND MAX(하차_정류장순번) - MIN(하차_정류장순번) <= 2) b " +
            "WHERE a.노선명 = b.노선명 AND a.승차_정류장순번 = b.승차_정류장순번 AND a.승차_정류장ars = b.승차_정류장ars AND a.하차_정류장ars = b.하차_정류장ars AND a.하차_정류장순번 <> b.최소하차순번 AND a.기준일자 = ?",
            기준일자,
            기준일자
        );
    }

    public int fixSameStopODByArs(String 기준일자) {
        return jdbcTemplate.update(
            "UPDATE analysis_table_final a " +
            "SET 하차_정류장순번 = a.승차_정류장순번 + 1, 하차_정류장ars = b.승차_정류장ars, 하차_정류장표준코드 = b.승차_정류장표준코드, 하차_정류장명 = b.승차_정류장명 " +
            "FROM analysis_table_final b " +
            "WHERE a.노선명 = b.노선명 AND a.기준일자 = b.기준일자 AND b.승차_정류장순번 = a.승차_정류장순번 + 1 AND a.승차_정류장ars = a.하차_정류장ars AND a.기준일자 = ?",
            기준일자
        );
    }
}