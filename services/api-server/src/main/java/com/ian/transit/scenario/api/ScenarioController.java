package com.ian.transit.scenario.api;

import com.ian.transit.scenario.application.ScenarioService;
import com.ian.transit.scenario.dto.ScenarioRequest;
import com.ian.transit.scenario.dto.ScenarioResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /api/scenarios/evaluate: what a route change would do to the
 * representative-day riders (see ScenarioEngine). Read only; the scenario is
 * not stored, and the page keeps it in its own link.
 *
 *   {"date": "weekday", "hours": "7-9", "operations": [
 *     {"type": "SHORT_TURN", "route": "143", "fromSeq": 20, "toSeq": 45, "share": 0.5},
 *     {"type": "SPLIT", "route": "302", "atSeq": 30},
 *     {"type": "CUT", "route": "5522A", "fromSeq": 1, "toSeq": 40},
 *     {"type": "NEW_ROUTE", "name": "N1", "stops": ["01001", "01002"], "headwayMin": 10}]}
 */
@RestController
@RequestMapping("/api/scenarios")
@RequiredArgsConstructor
public class ScenarioController {

    private final ScenarioService service;

    @PostMapping("/evaluate")
    public ResponseEntity<ScenarioResponse> evaluate(@RequestBody ScenarioRequest request) {
        return ResponseEntity.ok(service.evaluate(request));
    }
}
