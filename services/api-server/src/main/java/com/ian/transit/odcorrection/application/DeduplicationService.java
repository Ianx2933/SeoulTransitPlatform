package com.ian.transit.odcorrection.application;

import com.ian.transit.odcorrection.infrastructure.OdCorrectionJdbcRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Handles duplicate aggregation after correction
 */
@Service
@RequiredArgsConstructor
public class DeduplicationService {

    private static final Logger log = LoggerFactory.getLogger(DeduplicationService.class);

    private final OdCorrectionJdbcRepository jdbcRepository;

    /**
     * Deduplicate rows and sum passenger counts
     */
    public int deduplicateAndSum(String 기준일자) {
        int count = jdbcRepository.deduplicateAndSum(기준일자);
        log.info("Deduplication completed / 중복 합산 완료: {} [{}]", count, 기준일자);
        return count;
    }
}
