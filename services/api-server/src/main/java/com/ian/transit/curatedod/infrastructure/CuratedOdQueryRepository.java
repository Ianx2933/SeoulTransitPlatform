package com.ian.transit.curatedod.infrastructure;

import com.ian.transit.curatedod.dto.RouteOdSummaryResponse;
import com.ian.transit.curatedod.dto.StopOdMatrixResponse;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * Query repository for curated OD serving APIs
 */
public interface CuratedOdQueryRepository extends JpaRepository<CuratedOdRecord, CuratedOdRecordId> {

    List<CuratedOdRecord> findBy기준일자(String 기준일자, Pageable pageable);

    List<CuratedOdRecord> findBy기준일자And노선명(String 기준일자, String 노선명, Pageable pageable);

    @Query("SELECT new com.ian.transit.curatedod.dto.RouteOdSummaryResponse(" +
           "b.기준일자, b.노선명, SUM(b.승객수)) " +
           "FROM CuratedOdRecord b " +
           "WHERE b.기준일자 = :기준일자 " +
           "GROUP BY b.기준일자, b.노선명 " +
           "ORDER BY SUM(b.승객수) DESC")
    List<RouteOdSummaryResponse> summarizeByRoute(@Param("기준일자") String 기준일자, Pageable pageable);

    @Query("SELECT new com.ian.transit.curatedod.dto.StopOdMatrixResponse(" +
           "b.기준일자, b.노선명, b.승차정류장명, b.승차정류장ARS, " +
           "b.하차정류장명, b.하차정류장ARS, SUM(b.승객수)) " +
           "FROM CuratedOdRecord b " +
           "WHERE b.기준일자 = :기준일자 " +
           "AND b.노선명 = :노선명 " +
           "GROUP BY b.기준일자, b.노선명, b.승차정류장명, b.승차정류장ARS, b.하차정류장명, b.하차정류장ARS " +
           "ORDER BY SUM(b.승객수) DESC")
    List<StopOdMatrixResponse> findStopOdMatrix(
        @Param("기준일자") String 기준일자,
        @Param("노선명") String 노선명,
        Pageable pageable
    );
}
