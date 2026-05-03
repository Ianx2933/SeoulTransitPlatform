package com.ian.transit.odcorrection.application;

import com.ian.transit.odcorrection.infrastructure.OdCorrectionCommandRepository;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles virtual stop related corrections.
 * 
 * Virtual stops are normalised to ARS 00000 because they are operational placeholders,
 * not physical passenger boarding and alighting locations.
 * 
 * Remaining anomaly counts are logged instead of hidden so operators can decide whether
 * manual review is needed.
 */

@Service
@RequiredArgsConstructor
public class VirtualStopCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(VirtualStopCorrectionService.class);

    private final OdCorrectionCommandRepository commandRepository;
    private final OdCorrectionQueryRepository queryRepository;
    private final CorrectionQueryService correctionQueryService;

    public int fixEmptyArrivalStopName(String 기준일자) {
        int fixedCount = commandRepository.fixEmptyArrivalStopName(기준일자);
        int remaining = queryRepository.countEmptyArrivalStopName(기준일자);

        if (remaining > 0) {
            log.warn("Empty arrival stop names remain / 하차 정류장명 결측 잔여: {} [{}]", remaining, 기준일자);
        }

        return fixedCount;
    }

    public int fixVirtualStopArs(String 기준일자) {
        int boardingFixed = commandRepository.fixVirtualStopBoardingArs(기준일자);
        int alightingFixed = commandRepository.fixVirtualStopAlightingArs(기준일자);

        int remainingBoarding = queryRepository.countUnfixedVirtualBoardingArs(기준일자);
        int remainingAlighting = queryRepository.countUnfixedVirtualAlightingArs(기준일자);

        if (remainingBoarding > 0) {
            log.warn("Unfixed virtual boarding ARS remains / 승차 가상 정류장 ARS 잔여: {} [{}]", remainingBoarding, 기준일자);
        }
        if (remainingAlighting > 0) {
            log.warn("Unfixed virtual alighting ARS remains / 하차 가상 정류장 ARS 잔여: {} [{}]", remainingAlighting, 기준일자);
        }

        return boardingFixed + alightingFixed;
    }

    public int fixBoardingSequence(String 기준일자) {
        List<String> suspiciousRoutes = correctionQueryService.detectBidirectionalRoutes(기준일자);
        if (!suspiciousRoutes.isEmpty()) {
            log.warn("Manual review recommended for bidirectional routes / 양방향 노선 수동 확인 권장: {}", suspiciousRoutes);
        }

        int fixedCount = commandRepository.fixBoardingSequence(기준일자);
        int remaining = queryRepository.countNullBoardingSequence(기준일자);

        if (remaining > 0) {
            log.warn("Null boarding sequence remains / 승차순번 NULL 잔여: {} [{}]", remaining, 기준일자);
        }

        return fixedCount;
    }

    public int fixAlightingSequence(String 기준일자) {
        List<String> suspiciousRoutes = correctionQueryService.detectBidirectionalRoutes(기준일자);
        if (!suspiciousRoutes.isEmpty()) {
            log.warn("Manual review recommended before alighting sequence correction / 하차순번 보정 전 수동 확인 권장: {}", suspiciousRoutes);
        }

        int caseAFixed = commandRepository.fixAlightingSequenceCaseA(기준일자);
        int caseC1Fixed = commandRepository.fixAlightingSequenceCaseC1(기준일자);
        int caseC2Inserted = commandRepository.insertAnomalyDataCaseC2(기준일자);
        int caseC2Deleted = commandRepository.deleteAnomalyDataCaseC2(기준일자);
        int caseBFixed = commandRepository.fixAlightingSequenceCaseB(기준일자);

        int remaining = queryRepository.countNullAlightingSequence(기준일자);

        log.info(
            "Alighting sequence virtual-stop correction completed / 가상 정류장 하차순번 보정 완료: caseA={}, caseC1={}, caseC2Inserted={}, caseC2Deleted={}, caseB={}, remaining={}, date={}",
            caseAFixed, caseC1Fixed, caseC2Inserted, caseC2Deleted, caseBFixed, remaining, 기준일자
        );

        return caseAFixed + caseC1Fixed + caseBFixed;
    }

    public int fixVirtualStopBoarding(String 기준일자) {
        return commandRepository.fixVirtualStopBoarding(기준일자);
    }

    public int fixMidVirtualStopBoarding(String 기준일자) {
        return commandRepository.fixMidVirtualStopBoarding(기준일자);
    }

    public int fixVirtualStopSameOD(String 기준일자) {
        return commandRepository.fixVirtualStopSameOD(기준일자);
    }
}
