package com.ian.transit.odcorrection.application;

import com.ian.transit.odcorrection.infrastructure.OdCorrectionCommandRepository;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles sequence-related correction logic
 */
@Service
@RequiredArgsConstructor
public class SequenceCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(SequenceCorrectionService.class);

    private final OdCorrectionCommandRepository commandRepository;
    private final OdCorrectionJdbcRepository jdbcRepository;

    public int fixSequenceByArs(String 기준일자) {
        int fixedCount = commandRepository.fixSequenceByArs(기준일자);
        log.info("Boarding sequence correction completed / 승차순번 보정 완료: {} [{}]", fixedCount, 기준일자);
        return fixedCount;
    }

    public int fixAlightingSequenceByArs(String 기준일자) {
        int fixedCount = jdbcRepository.fixAlightingSequenceByArs(기준일자);
        log.info("Alighting sequence correction completed / 하차순번 보정 완료: {} [{}]", fixedCount, 기준일자);
        return fixedCount;
    }
}
