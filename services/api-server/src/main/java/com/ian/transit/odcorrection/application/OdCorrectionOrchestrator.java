package com.ian.transit.odcorrection.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates grouped OD correction workflows.
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
        int total = 0;
        total += virtualStopCorrectionService.fixVirtualStopArs(기준일자);
        total += virtualStopCorrectionService.fixBoardingSequence(기준일자);
        total += virtualStopCorrectionService.fixAlightingSequence(기준일자);
        total += virtualStopCorrectionService.fixEmptyArrivalStopName(기준일자);
        total += virtualStopCorrectionService.fixVirtualStopBoarding(기준일자);
        total += virtualStopCorrectionService.fixMidVirtualStopBoarding(기준일자);
        total += virtualStopCorrectionService.fixVirtualStopSameOD(기준일자);
        deduplicationService.deduplicateAndSum(기준일자);
        return total;
    }

    @Transactional
    public int fixSequence(String 기준일자) {
        int total = 0;
        total += sequenceCorrectionService.fixSequenceByArs(기준일자);
        total += sequenceCorrectionService.fixAlightingSequenceByArs(기준일자);
        deduplicationService.deduplicateAndSum(기준일자);
        return total;
    }

    @Transactional
    public int fixSameStopOD(String 기준일자) {
        int total = 0;
        total += sameStopCorrectionService.fixSameStopODBySequence(기준일자);
        total += sameStopCorrectionService.fixSameStopODByArs(기준일자);
        deduplicationService.deduplicateAndSum(기준일자);
        return total;
    }

    public int deduplicateAndSum(String 기준일자) {
        return deduplicationService.deduplicateAndSum(기준일자);
    }
}
