package com.ian.transit.curatedod.application;

import com.ian.transit.curatedod.dto.CuratedOdResponse;
import com.ian.transit.curatedod.dto.RouteOdSummaryResponse;
import com.ian.transit.curatedod.dto.StopOdMatrixResponse;
import com.ian.transit.curatedod.infrastructure.CuratedOdQueryRepository;
import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query service for curated OD serving APIs.
 * 
 * This service keeps serving logic separate from correction logic so that public API
 * reads do not accidentally mutate curated data.
 */
@Service
@RequiredArgsConstructor
public class CuratedOdQueryService {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 1000;

    private final CuratedOdQueryRepository queryRepository;

/**
 * Caps query size to prevent oversized API responses and accidental heavy queries.
 */

    public List<CuratedOdResponse> findByDate(String 기준일자, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        return queryRepository.findBy기준일자(기준일자, PageRequest.of(0, safeLimit))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public List<CuratedOdResponse> findByDateAndRoute(String 기준일자, String 노선명, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        return queryRepository.findBy기준일자And노선명(기준일자, 노선명, PageRequest.of(0, safeLimit))
            .stream()
            .map(this::toResponse)
            .toList();
    }

    public List<RouteOdSummaryResponse> summarizeByRoute(String 기준일자, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        return queryRepository.summarizeByRoute(기준일자, PageRequest.of(0, safeLimit));
    }

    public List<StopOdMatrixResponse> findStopOdMatrix(String 기준일자, String 노선명, Integer limit) {
        int safeLimit = normalizeLimit(limit);
        return queryRepository.findStopOdMatrix(기준일자, 노선명, PageRequest.of(0, safeLimit));
    }

    private CuratedOdResponse toResponse(CuratedOdRecord record) {
        return new CuratedOdResponse(
            record.get기준일자(),
            record.get노선명(),
            record.get전환노선ID(),
            record.get승차정류장순번(),
            record.get승차정류장ARS(),
            record.get승차정류장표준코드(),
            record.get승차정류장명(),
            record.get하차정류장순번(),
            record.get하차정류장ARS(),
            record.get하차정류장표준코드(),
            record.get하차정류장명(),
            record.get승객수()
        );
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }
}
