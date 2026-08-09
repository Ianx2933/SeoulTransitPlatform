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
 *
 * Read paths validate 기준일자 as well: it keeps the whole admin surface
 * uniform and turns malformed dates into a clear 400 instead of an empty
 * result.
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
        return queryRepository.findBy기준일자And승차정류장표준코드IsNull(
                CorrectionDateValidator.requireValid(기준일자)
        );
    }

    /**
     * Find rows whose alighting standard code is missing
     */
    public List<CuratedOdRecord> getNullAlightingStandardCode(String 기준일자) {
        return queryRepository.findBy기준일자And하차정류장표준코드IsNull(
                CorrectionDateValidator.requireValid(기준일자)
        );
    }

    /**
     * Detect suspicious bidirectional routes.
     */
    public List<String> detectBidirectionalRoutes(String 기준일자) {
        String validated기준일자 = CorrectionDateValidator.requireValid(기준일자);

        List<String> suspiciousRoutes = queryRepository.detectBidirectionalRoutes(validated기준일자);

        if (!suspiciousRoutes.isEmpty()) {
            log.warn(
                    "Suspicious bidirectional routes found / 양방향 노선 의심 케이스 발견: {} [{}]",
                    suspiciousRoutes,
                    validated기준일자
            );
        }

        return suspiciousRoutes;
    }
}
