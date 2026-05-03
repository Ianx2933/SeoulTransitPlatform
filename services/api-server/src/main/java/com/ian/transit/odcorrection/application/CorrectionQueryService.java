package com.ian.transit.odcorrection.application;

import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Query service for OD correction administration.
 *
 * This service is used to inspect correction targets and remaining anomalies before or
 * after running update workflows. 
 */

@Service
@RequiredArgsConstructor
public class CorrectionQueryService {

    private static final Logger log = LoggerFactory.getLogger(CorrectionQueryService.class);

    private final OdCorrectionQueryRepository queryRepository;

    /**
     * Find rows whose boarding standard code is missing
     */
    public List<CuratedOdRecord> getNullStandardCode(String 기준일자) {
        return queryRepository.findBy기준일자And승차정류장표준코드IsNull(기준일자);
    }

    /**
     * Find rows whose alighting standard code is missing
     */
    public List<CuratedOdRecord> getNullAlightingStandardCode(String 기준일자) {
        return queryRepository.findBy기준일자And하차정류장표준코드IsNull(기준일자);
    }

    /**
     * Detect suspicious bidirectional routes.
     */
    public List<String> detectBidirectionalRoutes(String 기준일자) {
        List<String> suspiciousRoutes = queryRepository.detectBidirectionalRoutes(기준일자);

        if (!suspiciousRoutes.isEmpty()) {
            log.warn("Suspicious bidirectional routes found / 양방향 노선 의심 케이스 발견: {} [{}]", suspiciousRoutes, 기준일자);
        }

        return suspiciousRoutes;
    }
}
