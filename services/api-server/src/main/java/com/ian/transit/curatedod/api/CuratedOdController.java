package com.ian.transit.curatedod.api;

import com.ian.transit.curatedod.application.CuratedOdQueryService;
import com.ian.transit.curatedod.dto.CuratedOdResponse;
import com.ian.transit.curatedod.dto.RouteOdSummaryResponse;
import com.ian.transit.curatedod.dto.StopOdMatrixResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Controller for curated OD serving APIs.
 */
@RestController
@RequestMapping("/api/curated-od")
@RequiredArgsConstructor
public class CuratedOdController {

    private final CuratedOdQueryService queryService;

    @GetMapping
    public ResponseEntity<List<CuratedOdResponse>> findByDate(
        @RequestParam("date") String 기준일자,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.findByDate(기준일자, limit));
    }

    @GetMapping("/routes/{routeName}")
    public ResponseEntity<List<CuratedOdResponse>> findByDateAndRoute(
        @RequestParam("date") String 기준일자,
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.findByDateAndRoute(기준일자, 노선명, limit));
    }

    @GetMapping("/summary/routes")
    public ResponseEntity<List<RouteOdSummaryResponse>> summarizeByRoute(
        @RequestParam("date") String 기준일자,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.summarizeByRoute(기준일자, limit));
    }

    @GetMapping("/routes/{routeName}/matrix")
    public ResponseEntity<List<StopOdMatrixResponse>> findStopOdMatrix(
        @RequestParam("date") String 기준일자,
        @PathVariable("routeName") String 노선명,
        @RequestParam(value = "limit", required = false) Integer limit
    ) {
        return ResponseEntity.ok(queryService.findStopOdMatrix(기준일자, 노선명, limit));
    }
}
