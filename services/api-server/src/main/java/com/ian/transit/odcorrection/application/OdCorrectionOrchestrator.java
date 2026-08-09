package com.ian.transit.odcorrection.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates grouped OD correction workflows.
 *
 * Correction steps are ordered because later fixes depend on earlier normalisation such as
 * virtual stop handling and sequence correction.
 *
 * Transactional boundaries protect each correction workflow from partial updates.
 *
 * Every public method validates 기준일자 (yyyyMMdd) before touching data —
 * these workflows run destructive SQL, so malformed or hostile input must be
 * rejected at the boundary.
 */
@Service
@RequiredArgsConstructor
public class OdCorrectionOrchestrator {

    private final VirtualStopCorrectionService virtualStopCorrectionService;
    private final SequenceCorrectionService sequenceCorrectionService;
    private final SameStopCorrectionService sameStopCorrectionService;
    private final DeduplicationService deduplicationService;

    @Transactional
    public int fixVirtualStop(String 기준일자) {
        String validated기준일자 = CorrectionDateValidator.requireValid(기준일자);
        int total = 0;
        total += virtualStopCorrectionService.fixVirtualStopArs(validated기준일자);
        total += virtualStopCorrectionService.fixBoardingSequence(validated기준일자);
        total += virtualStopCorrectionService.fixAlightingSequence(validated기준일자);
        total += virtualStopCorrectionService.fixEmptyArrivalStopName(validated기준일자);
        total += virtualStopCorrectionService.fixVirtualStopBoarding(validated기준일자);
        total += virtualStopCorrectionService.fixMidVirtualStopBoarding(validated기준일자);
        total += virtualStopCorrectionService.fixVirtualStopSameOD(validated기준일자);
        deduplicationService.deduplicateAndSum(validated기준일자);
        return total;
    }

    @Transactional
    public int fixSequence(String 기준일자) {
        String validated기준일자 = CorrectionDateValidator.requireValid(기준일자);
        int total = 0;
        total += sequenceCorrectionService.fixSequenceByArs(validated기준일자);
        total += sequenceCorrectionService.fixAlightingSequenceByArs(validated기준일자);
        deduplicationService.deduplicateAndSum(validated기준일자);
        return total;
    }

    @Transactional
    public int fixSameStopOD(String 기준일자) {
        String validated기준일자 = CorrectionDateValidator.requireValid(기준일자);
        int total = 0;
        total += sameStopCorrectionService.fixSameStopODBySequence(validated기준일자);
        total += sameStopCorrectionService.fixSameStopODByArs(validated기준일자);
        deduplicationService.deduplicateAndSum(validated기준일자);
        return total;
    }

    public int deduplicateAndSum(String 기준일자) {
        return deduplicationService.deduplicateAndSum(
                CorrectionDateValidator.requireValid(기준일자)
        );
    }
}
