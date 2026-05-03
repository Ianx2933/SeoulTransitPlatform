package com.ian.transit.congestion.api;

import com.ian.transit.congestion.application.CongestionQueryService;
import com.ian.transit.congestion.dto.CongestionResponse;
import com.ian.transit.congestion.dto.SectionCongestionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST controller that exposes bus route congestion query APIs.
 * Controller only handles HTTP request/response mapping.
 */
@RestController
@RequestMapping("/api/congestion")
@RequiredArgsConstructor
public class CongestionController {

    private final CongestionQueryService congestionQueryService;

    /**
     * Returns congestion information for a single route on a specific date.
     * Example: GET /api/congestion/143?date=20251111
     */
    @GetMapping("/{routeName}")
    public ResponseEntity<List<CongestionResponse>> getRouteCongestion(
            @PathVariable String routeName,
            @RequestParam String date
    ) {
        return ResponseEntity.ok(
                congestionQueryService.getRouteCongestion(routeName, date)
        );
    }

    /**
     * Returns congestion information for multiple routes.
     * Example: GET /api/congestion?routes=143,401,N13&date=20251111
     */
    @GetMapping
    public ResponseEntity<List<CongestionResponse>> getRoutesCongestion(
            @RequestParam List<String> routes,
            @RequestParam String date
    ) {
        return ResponseEntity.ok(
                congestionQueryService.getRoutesCongestion(routes, date)
        );
    }

    /**
     * Returns ordered stop names for a route.
     * Useful for autocomplete/select boxes.
     * Example: GET /api/congestion/stops?route=영등포10&date=20251111
     */
    @GetMapping("/stops")
    public ResponseEntity<List<String>> getStopsByRoute(
            @RequestParam String route,
            @RequestParam String date
    ) {
        return ResponseEntity.ok(
                congestionQueryService.getStopsByRoute(route, date)
        );
    }

    /**
     * Returns congestion information between two stops in a route.
     * Example: GET /api/congestion/section?route=영등포10&from=영등포역&to=당산역&date=20251111
     */
    @GetMapping("/section")
    public ResponseEntity<SectionCongestionResponse> getSectionCongestion(
            @RequestParam String route,
            @RequestParam String from,
            @RequestParam String to,
            @RequestParam String date
    ) {
        return ResponseEntity.ok(
                congestionQueryService.getSectionCongestion(route, from, to, date)
        );
    }
}
