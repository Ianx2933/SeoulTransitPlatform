package com.ian.transit.odcorrection.infrastructure;

import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import com.ian.transit.curatedod.infrastructure.CuratedOdRecordId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Command/update repository for JPQL and native update statements
 */
public interface OdCorrectionCommandRepository extends JpaRepository<CuratedOdRecord, CuratedOdRecordId> {

    @Modifying
    @Query("UPDATE CuratedOdRecord b SET b.승차정류장표준코드 = :standardCode WHERE b.기준일자 = :기준일자 AND b.노선명 = :노선명 AND b.승차정류장ARS = :승차ARS AND b.하차정류장ARS = :하차ARS")
    void update승차정류장표준코드(@Param("기준일자") String 기준일자, @Param("노선명") String 노선명, @Param("승차ARS") String 승차ARS, @Param("하차ARS") String 하차ARS, @Param("standardCode") String standardCode);

    @Modifying
    @Query("UPDATE CuratedOdRecord b SET b.하차정류장표준코드 = :standardCode WHERE b.기준일자 = :기준일자 AND b.노선명 = :노선명 AND b.승차정류장ARS = :승차ARS AND b.하차정류장ARS = :하차ARS")
    void update하차정류장표준코드(@Param("기준일자") String 기준일자, @Param("노선명") String 노선명, @Param("승차ARS") String 승차ARS, @Param("하차ARS") String 하차ARS, @Param("standardCode") String standardCode);

    @Modifying
    @Query("UPDATE CuratedOdRecord b SET b.하차정류장명 = b.승차정류장명 WHERE b.기준일자 = :기준일자 AND (b.하차정류장명 IS NULL OR b.하차정류장명 = '') AND b.하차정류장표준코드 = '277102436'")
    int fixEmptyArrivalStopName(@Param("기준일자") String 기준일자);

    @Modifying
    @Query("UPDATE CuratedOdRecord b SET b.승차정류장ARS = '00000' WHERE b.기준일자 = :기준일자 AND (b.승차정류장명 LIKE '%가상%' OR b.승차정류장명 LIKE '%기점가상%' OR b.승차정류장명 LIKE '%경유%') AND (b.승차정류장ARS <> '06137' OR b.승차정류장ARS IS NULL)")
    int fixVirtualStopBoardingArs(@Param("기준일자") String 기준일자);

    @Modifying
    @Query("UPDATE CuratedOdRecord b SET b.하차정류장ARS = '00000' WHERE b.기준일자 = :기준일자 AND (b.하차정류장명 LIKE '%가상%' OR b.하차정류장명 LIKE '%기점가상%' OR b.하차정류장명 LIKE '%경유%') AND (b.하차정류장ARS <> '06137' OR b.하차정류장ARS IS NULL)")
    int fixVirtualStopAlightingArs(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final SET 승차_정류장순번 = 0 WHERE 기준일자 = :기준일자 AND 승차_정류장ars = '00000' AND 승차_정류장순번 IS NULL", nativeQuery = true)
    int fixBoardingSequence(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final SET 하차_정류장순번 = 0 WHERE 기준일자 = :기준일자 AND 승차_정류장순번 = 0 AND 승차_정류장ars = '00000' AND 하차_정류장ars = '00000' AND 하차_정류장순번 IS NULL", nativeQuery = true)
    int fixAlightingSequenceCaseA(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final SET 하차_정류장명 = 승차_정류장명, 하차_정류장ars = 승차_정류장ars, 하차_정류장표준코드 = 승차_정류장표준코드, 하차_정류장순번 = 승차_정류장순번 WHERE 기준일자 = :기준일자 AND 하차_정류장ars = '00000' AND 하차_정류장순번 IS NULL AND 승차_정류장순번 BETWEEN 1 AND 10", nativeQuery = true)
    int fixAlightingSequenceCaseC1(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "INSERT INTO anomaly_data SELECT a.기준일자, a.노선명, a.전환_노선id, a.승차_정류장순번, a.승차_정류장ars, a.승차_정류장표준코드, a.승차_정류장명, a.하차_정류장순번, a.하차_정류장ars, a.하차_정류장표준코드, a.하차_정류장명, a.승객수 FROM analysis_table_final a INNER JOIN (SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 FROM analysis_table_final WHERE 승차_정류장순번 IS NOT NULL AND 기준일자 = :기준일자 GROUP BY 노선명) b ON a.노선명 = b.노선명 WHERE a.기준일자 = :기준일자 AND a.하차_정류장ars = '00000' AND a.하차_정류장순번 IS NULL AND a.승차_정류장순번::FLOAT / b.최대순번 * 100 < 48 AND a.승차_정류장순번 >= 11", nativeQuery = true)
    int insertAnomalyDataCaseC2(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "DELETE FROM analysis_table_final a USING (SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 FROM analysis_table_final WHERE 승차_정류장순번 IS NOT NULL AND 기준일자 = :기준일자 GROUP BY 노선명) b WHERE a.노선명 = b.노선명 AND a.기준일자 = :기준일자 AND a.하차_정류장ars = '00000' AND a.하차_정류장순번 IS NULL AND a.승차_정류장순번::FLOAT / b.최대순번 * 100 < 48 AND a.승차_정류장순번 >= 11", nativeQuery = true)
    int deleteAnomalyDataCaseC2(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final a SET 하차_정류장순번 = b.최대순번 + 1 FROM (SELECT 노선명, MAX(승차_정류장순번) AS 최대순번 FROM analysis_table_final WHERE 승차_정류장순번 IS NOT NULL AND 기준일자 = :기준일자 GROUP BY 노선명) b WHERE a.노선명 = b.노선명 AND a.기준일자 = :기준일자 AND a.하차_정류장ars = '00000' AND a.하차_정류장순번 IS NULL", nativeQuery = true)
    int fixAlightingSequenceCaseB(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final SET 하차_정류장순번 = 승차_정류장순번, 하차_정류장ars = 승차_정류장ars, 하차_정류장표준코드 = 승차_정류장표준코드, 하차_정류장명 = 승차_정류장명 WHERE 기준일자 = :기준일자 AND 승차_정류장ars <> '00000' AND 하차_정류장ars = '00000' AND 하차_정류장순번 IS NOT NULL", nativeQuery = true)
    int fixVirtualStopBoarding(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final SET 승차_정류장순번 = 하차_정류장순번, 승차_정류장ars = 하차_정류장ars, 승차_정류장표준코드 = 하차_정류장표준코드, 승차_정류장명 = 하차_정류장명 WHERE 기준일자 = :기준일자 AND 승차_정류장ars = '00000' AND 승차_정류장순번 <> 0", nativeQuery = true)
    int fixMidVirtualStopBoarding(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final SET 하차_정류장순번 = 승차_정류장순번 + 1 WHERE 기준일자 = :기준일자 AND 승차_정류장ars = '00000' AND 하차_정류장ars = '00000' AND 승차_정류장순번 = 하차_정류장순번", nativeQuery = true)
    int fixVirtualStopSameOD(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final a SET 승차_정류장순번 = b.최빈순번 FROM ( SELECT 노선명, 승차_정류장ars, 승차_정류장순번 AS 최빈순번 FROM ( SELECT 노선명, 승차_정류장ars, 승차_정류장순번, ROW_NUMBER() OVER (PARTITION BY 노선명, 승차_정류장ars ORDER BY COUNT(*) DESC) AS rn FROM analysis_table_final WHERE 승차_정류장ars <> '00000' AND 기준일자 = :기준일자 GROUP BY 노선명, 승차_정류장ars, 승차_정류장순번 ) ranked WHERE rn = 1 ) b WHERE a.노선명 = b.노선명 AND a.승차_정류장ars = b.승차_정류장ars AND a.승차_정류장순번 <> b.최빈순번 AND a.기준일자 = :기준일자", nativeQuery = true)
    int fixSequenceByArs(@Param("기준일자") String 기준일자);

    @Modifying
    @Query(value = "UPDATE analysis_table_final SET 하차_정류장순번 = 승차_정류장순번 + 1 WHERE 기준일자 = :기준일자 AND 승차_정류장순번 = 하차_정류장순번 AND 승차_정류장ars = 하차_정류장ars AND 승차_정류장ars <> '00000'", nativeQuery = true)
    int fixSameStopODBySequence(@Param("기준일자") String 기준일자);
}