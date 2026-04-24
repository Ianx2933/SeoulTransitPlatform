package com.ian.transit.odcorrection.api;

import com.ian.transit.curatedod.infrastructure.CuratedOdRecord;
import com.ian.transit.odcorrection.application.CorrectionQueryService;
import com.ian.transit.odcorrection.application.OdCorrectionOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Administrative controller for OD correction workflows.
 */
@RestController
@RequestMapping("/api/od-correction")
@RequiredArgsConstructor
public class OdCorrectionController {

    private final OdCorrectionOrchestrator orchestrator;
    private final CorrectionQueryService correctionQueryService;

    @GetMapping("/null-boarding-standard-code")
    public ResponseEntity<List<CuratedOdRecord>> getNullBoardingStandardCode(@RequestParam("date") String 기준일자) {
        return ResponseEntity.ok(correctionQueryService.getNullStandardCode(기준일자));
    }

    @GetMapping("/null-alighting-standard-code")
    public ResponseEntity<List<CuratedOdRecord>> getNullAlightingStandardCode(@RequestParam("date") String 기준일자) {
        return ResponseEntity.ok(correctionQueryService.getNullAlightingStandardCode(기준일자));
    }

    @GetMapping("/detect-bidirectional")
    public ResponseEntity<List<String>> detectBidirectionalRoutes(@RequestParam("date") String 기준일자) {
        return ResponseEntity.ok(correctionQueryService.detectBidirectionalRoutes(기준일자));
    }

    @PostMapping("/virtual-stop")
    public ResponseEntity<String> fixVirtualStop(@RequestParam("date") String 기준일자) {
        int total = orchestrator.fixVirtualStop(기준일자);
        return ResponseEntity.ok("Virtual stop correction completed / 가상 정류장 보정 완료: " + total);
    }

    @PostMapping("/sequence")
    public ResponseEntity<String> fixSequence(@RequestParam("date") String 기준일자) {
        int total = orchestrator.fixSequence(기준일자);
        return ResponseEntity.ok("Sequence correction completed / 순번 보정 완료: " + total);
    }

    @PostMapping("/same-stop")
    public ResponseEntity<String> fixSameStopOd(@RequestParam("date") String 기준일자) {
        int total = orchestrator.fixSameStopOD(기준일자);
        return ResponseEntity.ok("Same-stop OD correction completed / 승하차 동일 OD 보정 완료: " + total);
    }

    @PostMapping("/deduplicate")
    public ResponseEntity<String> deduplicate(@RequestParam("date") String 기준일자) {
        int total = orchestrator.deduplicateAndSum(기준일자);
        return ResponseEntity.ok("Deduplication completed / 중복 합산 완료: " + total);
    }
}