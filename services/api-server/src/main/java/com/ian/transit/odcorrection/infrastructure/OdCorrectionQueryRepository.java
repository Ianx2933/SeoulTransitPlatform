package com.ian.transit.odcorrection.infrastructure;

import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import com.ian.transit.curatedod.infrastructure.CuratedOdRecordId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Query-only repository for OD correction workflows.
 * 
 * These queries are used to detect missing codes, virtual-stop residues,
 * and suspicious route structures before/after correction.
 */

public interface OdCorrectionQueryRepository extends JpaRepository<CuratedOdRecord, CuratedOdRecordId> {

    List<CuratedOdRecord> findBy기준일자And승차정류장표준코드IsNull(String 기준일자);

    List<CuratedOdRecord> findBy기준일자And하차정류장표준코드IsNull(String 기준일자);

    @Query("SELECT DISTINCT b.노선명 FROM CuratedOdRecord b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND b.승차정류장순번 = 0 " +
           "AND b.승차정류장ARS = '00000' " +
           "AND b.노선명 IN (" +
           "    SELECT b2.노선명 FROM CuratedOdRecord b2 " +
           "    WHERE b2.기준일자 = :기준일자 " +
           "    AND b2.승차정류장순번 IS NOT NULL " +
           "    AND b2.승차정류장ARS <> '00000' " +
           "    GROUP BY b2.노선명 " +
           "    HAVING MAX(b2.승차정류장순번) > 100" +
           ")")
    List<String> detectBidirectionalRoutes(@Param("기준일자") String 기준일자);

    @Query("SELECT COUNT(b) FROM CuratedOdRecord b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.하차정류장명 IS NULL OR b.하차정류장명 = '') " +
           "AND b.하차정류장표준코드 = '277102436'")
    int countEmptyArrivalStopName(@Param("기준일자") String 기준일자);

    @Query("SELECT COUNT(b) FROM CuratedOdRecord b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.승차정류장명 LIKE '%가상%' OR b.승차정류장명 LIKE '%기점가상%' OR b.승차정류장명 LIKE '%경유%') " +
           "AND b.승차정류장ARS <> '00000' " +
           "AND b.승차정류장ARS <> '06137'")
    int countUnfixedVirtualBoardingArs(@Param("기준일자") String 기준일자);

    @Query("SELECT COUNT(b) FROM CuratedOdRecord b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND (b.하차정류장명 LIKE '%가상%' OR b.하차정류장명 LIKE '%기점가상%' OR b.하차정류장명 LIKE '%경유%') " +
           "AND b.하차정류장ARS <> '00000' " +
           "AND b.하차정류장ARS <> '06137'")
    int countUnfixedVirtualAlightingArs(@Param("기준일자") String 기준일자);

    @Query(value =
           "SELECT COUNT(*) FROM analysis_table_final " +
           "WHERE 기준일자 = :기준일자 " +
           "AND 승차_정류장ars = '00000' " +
           "AND 승차_정류장순번 IS NULL",
           nativeQuery = true)
    int countNullBoardingSequence(@Param("기준일자") String 기준일자);

    @Query("SELECT COUNT(b) FROM CuratedOdRecord b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND b.하차정류장ARS = '00000' " +
           "AND b.하차정류장순번 IS NULL")
    int countNullAlightingSequence(@Param("기준일자") String 기준일자);
}
