package com.ian.transit.curatedod.api;

import com.ian.transit.curatedod.application.CuratedOdQueryService;
import com.ian.transit.curatedod.dto.CuratedOdResponse;
import com.ian.transit.curatedod.dto.RouteOdSummaryResponse;
import com.ian.transit.curatedod.dto.StopOdMatrixResponse;
import com.ian.transit.common.TypicalDay;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for public curated OD serving APIs.
 * 
 * These endpoints expose corrected OD data for analysis and visualization,
 * while correction operations are kept in the OD correction module.
 */
@RestController
@RequestMapping("/api/curated-od")
@RequiredArgsConstructor
public class CuratedOdController {

    private final CuratedOdQueryService queryService;

    @GetMapping
    public ResponseEntity<List<CuratedOdResponse>> findByDate(
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.findByDate(TypicalDay.label(기준일자), limit));
    }

    @GetMapping("/routes/{routeName}")
    public ResponseEntity<List<CuratedOdResponse>> findByDateAndRoute(
        @RequestParam(value = "date", required = false) String 기준일자,
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.findByDateAndRoute(TypicalDay.label(기준일자), 노선명, limit));
    }

    @GetMapping("/summary/routes")
    public ResponseEntity<List<RouteOdSummaryResponse>> summarizeByRoute(
        @RequestParam(value = "date", required = false) String 기준일자,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.summarizeByRoute(TypicalDay.label(기준일자), limit));
    }

    @GetMapping("/routes/{routeName}/matrix")
    public ResponseEntity<List<StopOdMatrixResponse>> findStopOdMatrix(
        @RequestParam(value = "date", required = false) String 기준일자,
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.findStopOdMatrix(TypicalDay.label(기준일자), 노선명, limit));
    }
}
