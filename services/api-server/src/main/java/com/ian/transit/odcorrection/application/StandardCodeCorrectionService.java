package com.ian.transit.odcorrection.application;

import com.ian.transit.common.client.SeoulBusApiClient;
import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionCommandRepository;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionQueryRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Handles standard code correction logic.
 * 
 * Standard stop codes are resolved through the Seoul bus API because ARS alone is not always
 * sufficient for stable among the dataset joins.
 * 
 * External API failure is tolerated so that one failed lookup does not stop the entire correction workflow.
 */

@Service
@RequiredArgsConstructor
public class StandardCodeCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(StandardCodeCorrectionService.class);

    private final OdCorrectionQueryRepository queryRepository;
    private final OdCorrectionCommandRepository commandRepository;
    private final SeoulBusApiClient seoulBusApiClient;

    public int fixNullStandardCodes(String 기준일자) {
        int fixedCount = 0;
        List<CuratedOdRecord> rows = queryRepository.findBy기준일자And승차정류장표준코드IsNull(기준일자);

        for (CuratedOdRecord row : rows) {
            String standardCode = seoulBusApiClient.getStandardCodeByArs(row.get승차정류장ARS());
            if (standardCode != null && !standardCode.isBlank()) {
                commandRepository.update승차정류장표준코드(
                    기준일자,
                    row.get노선명(),
                    row.get승차정류장ARS(),
                    row.get하차정류장ARS(),
                    standardCode
                );
                fixedCount++;
            }
        }

        log.info("Boarding standard code correction completed / 승차 표준코드 보정 완료: {} [{}]", fixedCount, 기준일자);
        return fixedCount;
    }

    public int fixNullAlightingStandardCodes(String 기준일자) {
        int fixedCount = 0;
        List<CuratedOdRecord> rows = queryRepository.findBy기준일자And하차정류장표준코드IsNull(기준일자);

        for (CuratedOdRecord row : rows) {
            String standardCode = seoulBusApiClient.getStandardCodeByArs(row.get하차정류장ARS());
            if (standardCode != null && !standardCode.isBlank()) {
                commandRepository.update하차정류장표준코드(
                    기준일자,
                    row.get노선명(),
                    row.get승차정류장ARS(),
                    row.get하차정류장ARS(),
                    standardCode
                );
                fixedCount++;
            }
        }

        log.info("Alighting standard code correction completed / 하차 표준코드 보정 완료: {} [{}]", fixedCount, 기준일자);
        return fixedCount;
    }
}
