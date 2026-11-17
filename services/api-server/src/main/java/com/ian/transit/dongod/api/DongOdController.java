package com.ian.transit.dongod.api;

import com.ian.transit.dongod.application.DongOdService;
import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import com.ian.transit.dongod.dto.DongRoutesResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * District (행정동) OD on the typical day, all modes, from TOPIS district OD.
 *
 *   GET /api/dong-od/summary?date=weekday                    departures and arrivals per district
 *   GET /api/dong-od/{code}?date=weekday&direction=from&limit=15
 *   GET /api/dong-od/{code}/routes?date=weekday              bus routes boarding in the district
 */
@RestController
@RequestMapping("/api/dong-od")
@RequiredArgsConstructor
public class DongOdController {

    private final DongOdService service;

    @GetMapping("/summary")
    public ResponseEntity<List<DongOdSummaryRow>> summary(
        @RequestParam(value = "date", required = false) String date
    ) {
        return ResponseEntity.ok(service.summary(date));
    }

    @GetMapping("/{code}")
    public ResponseEntity<DongOdResponse> flows(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "direction", required = false) String direction,
        @RequestParam(value = "limit", defaultValue = "15") int limit
    ) {
        return ResponseEntity.ok(service.flows(date, code, direction, limit));
    }

    @GetMapping("/{code}/routes")
    public ResponseEntity<DongRoutesResponse> routes(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date
    ) {
        return ResponseEntity.ok(service.routes(date, code));
    }
}
