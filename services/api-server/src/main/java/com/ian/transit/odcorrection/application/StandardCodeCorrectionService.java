package com.ian.transit.odcorrection.application;

import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionCommandRepository;
import com.ian.transit.odcorrection.infrastructure.OdCorrectionQueryRepository;
import com.ian.transit.odcorrection.infrastructure.StopMasterLookup;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Fills missing standard stop codes from the local stop master.
 *
 * Earlier versions called the T-data stop API, which returned the same stop for
 * every request. Runs on 2023-10-17, 2024-10-19 and 2025-10-14 wrote that stop's
 * code (164000626, an Incheon stop no Seoul route serves) into 396 rows; they were
 * cleared on 2026-11-04 and a copy kept in analysis_table_final_bad_stdcode_20261104.
 *
 * Stops the master cannot resolve unambiguously are left empty and counted in
 * the log, so a run that resolves nothing is visible rather than reported as zero
 * fixes with no explanation.
 */
@Service
@RequiredArgsConstructor
public class StandardCodeCorrectionService {

    private static final Logger log = LoggerFactory.getLogger(StandardCodeCorrectionService.class);

    private final OdCorrectionQueryRepository queryRepository;
    private final OdCorrectionCommandRepository commandRepository;
    private final StopMasterLookup stopMasterLookup;

    public int fixNullStandardCodes(String 기준일자) {
        int fixedCount = 0;
        List<CuratedOdRecord> rows = queryRepository.findBy기준일자And승차정류장표준코드IsNull(기준일자);

        for (CuratedOdRecord row : rows) {
            Optional<String> standardCode = stopMasterLookup.findStandardCodeByArs(row.get승차정류장ARS());
            if (standardCode.isPresent()) {
                commandRepository.update승차정류장표준코드(
                    기준일자,
                    row.get노선명(),
                    row.get승차정류장ARS(),
                    row.get하차정류장ARS(),
                    standardCode.get()
                );
                fixedCount++;
            }
        }

        logResult("Boarding / 승차", 기준일자, rows.size(), fixedCount);
        return fixedCount;
    }

    public int fixNullAlightingStandardCodes(String 기준일자) {
        int fixedCount = 0;
        List<CuratedOdRecord> rows = queryRepository.findBy기준일자And하차정류장표준코드IsNull(기준일자);

        for (CuratedOdRecord row : rows) {
            Optional<String> standardCode = stopMasterLookup.findStandardCodeByArs(row.get하차정류장ARS());
            if (standardCode.isPresent()) {
                commandRepository.update하차정류장표준코드(
                    기준일자,
                    row.get노선명(),
                    row.get승차정류장ARS(),
                    row.get하차정류장ARS(),
                    standardCode.get()
                );
                fixedCount++;
            }
        }

        logResult("Alighting / 하차", 기준일자, rows.size(), fixedCount);
        return fixedCount;
    }

    private void logResult(String side, String 기준일자, int candidates, int fixed) {
        int unresolved = candidates - fixed;
        if (unresolved > 0) {
            log.warn("{} standard code correction [{}]: {} of {} rows fixed, {} left empty "
                    + "(ARS not in bus_stop_location or mapped to more than one node)",
                    side, 기준일자, fixed, candidates, unresolved);
        } else {
            log.info("{} standard code correction [{}]: {} of {} rows fixed", side, 기준일자, fixed, candidates);
        }
    }
}
