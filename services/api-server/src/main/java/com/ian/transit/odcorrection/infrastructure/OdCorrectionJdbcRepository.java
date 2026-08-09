package com.ian.transit.odcorrection.infrastructure;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * JDBC-heavy repository for long SQL and temporary table operations.
 *
 * JdbcTemplate is used here because deduplication and bulk correction require
 * PostgreSQL-specific SQL, temporary tables, and set-based operations.
 *
 * SECURITY NOTE: every statement in this class binds 기준일자 through a JDBC
 * parameter. The previous implementation interpolated 기준일자 directly into
 * the CREATE TEMP TABLE ... AS SELECT statement, which was an SQL injection
 * vector reachable from the /api/od-correction endpoints. PostgreSQL does not
 * accept bind parameters inside CTAS, so the temp table is now created empty
 * (WHERE false) and populated with a separate, fully parameterized INSERT.
 */
@Repository
@RequiredArgsConstructor
public class OdCorrectionJdbcRepository {

    private final JdbcTemplate jdbcTemplate;

    private static final String DEDUP_GROUP_COLUMNS =
            "노선명, 전환_노선id, 승차_정류장순번, 승차_정류장ars, 승차_정류장명, "
                    + "하차_정류장순번, 하차_정류장ars, 하차_정류장표준코드, 하차_정류장명";

    private static final String DEDUP_SELECT_LIST =
            "MIN(기준일자) AS 기준일자, " + DEDUP_GROUP_COLUMNS + ", SUM(승객수) AS 승객수";

    /**
     * Aggregates duplicated OD rows after correction while preserving total passengers.
     *
     * A temporary table is used to avoid row-by-row updates and keep the
     * operation set based in PostgreSQL.
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

        // Step 1: create the temp table with the correct column names and
        // types but zero rows (WHERE false). No user input is present in this
        // statement, so plain execute() is safe.
        jdbcTemplate.execute(
            "CREATE TEMP TABLE 중복합산임시 AS " +
            "SELECT " + DEDUP_SELECT_LIST + " " +
            "FROM analysis_table_final WHERE false " +
            "GROUP BY " + DEDUP_GROUP_COLUMNS
        );

        // Step 2: populate it with a parameterized INSERT — 기준일자 is bound,
        // never concatenated.
        jdbcTemplate.update(
            "INSERT INTO 중복합산임시 " +
            "SELECT " + DEDUP_SELECT_LIST + " " +
            "FROM analysis_table_final WHERE 기준일자 = ? " +
            "GROUP BY " + DEDUP_GROUP_COLUMNS + " " +
            "HAVING COUNT(*) > 1",
            기준일자
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
