package com.ian.transit.odcorrection.application;

import com.ian.transit.odcorrection.infrastructure.OdCorrectionCommandRepository;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles same-stop OD correction logic
 */
@Service
@RequiredArgsConstructor
public class SameStopCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(SameStopCorrectionService.class);

    private final OdCorrectionCommandRepository commandRepository;
    private final OdCorrectionJdbcRepository jdbcRepository;

    public int fixSameStopODBySequence(String 기준일자) {
        int fixedCount = commandRepository.fixSameStopODBySequence(기준일자);
        log.info("Same-stop OD by sequence correction completed / 순번 기준 동일 OD 보정 완료: {} [{}]", fixedCount, 기준일자);
        return fixedCount;
    }

    public int fixSameStopODByArs(String 기준일자) {
        int fixedCount = jdbcRepository.fixSameStopODByArs(기준일자);
        log.info("Same-stop OD by ARS correction completed / ARS 기준 동일 OD 보정 완료: {} [{}]", fixedCount, 기준일자);
        return fixedCount;
    }
}
