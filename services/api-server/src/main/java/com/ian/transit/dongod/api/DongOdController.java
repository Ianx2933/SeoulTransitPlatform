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
 * District OD on the typical day by rail and bus, from TOPIS district OD, for
 * a 행정동 (8-digit code), a 구 (5-digit code, level=gu) or Seoul (code 11,
 * level=city).
 *
 *   GET /api/dong-od/summary?date=weekday&level=dong|gu|city
 *   GET /api/dong-od/{code}?date=weekday&level=dong&dest=dong|gu&direction=from|to&limit=15
 *   GET /api/dong-od/{code}/routes?date=weekday&level=dong|gu
 */
@RestController
@RequestMapping("/api/dong-od")
@RequiredArgsConstructor
public class DongOdController {

    private final DongOdService service;

    @GetMapping("/summary")
    public ResponseEntity<List<DongOdSummaryRow>> summary(
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level
    ) {
        return ResponseEntity.ok(service.summary(date, level));
    }

    @GetMapping("/{code}")
    public ResponseEntity<DongOdResponse> flows(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level,
        @RequestParam(value = "dest", required = false) String dest,
        @RequestParam(value = "direction", required = false) String direction,
        @RequestParam(value = "limit", defaultValue = "15") int limit
    ) {
        return ResponseEntity.ok(service.flows(date, level, code, dest, direction, limit));
    }

    @GetMapping("/{code}/routes")
    public ResponseEntity<DongRoutesResponse> routes(
        @PathVariable("code") String code,
        @RequestParam(value = "date", required = false) String date,
        @RequestParam(value = "level", required = false) String level
    ) {
        return ResponseEntity.ok(service.routes(date, level, code));
    }
}
