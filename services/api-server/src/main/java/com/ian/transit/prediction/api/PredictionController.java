package com.ian.transit.prediction.api;

import com.ian.transit.prediction.application.PredictionService;
import com.ian.transit.prediction.dto.HourlyPredictionResponse;
import com.ian.transit.prediction.dto.WeightUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST controller for prediction APIs.
 * 
 * The controller exposes prediction results while keeping model inference and
 * feature generation outside the web layer.
 */

@RestController
@RequestMapping("/api/prediction")
@RequiredArgsConstructor
public class PredictionController {

    private final PredictionService predictionService;

    /**
     * Predicts hourly demand using date-derived dayOfWeek/isHoliday features.
     */
    @GetMapping("/hourly")
    public ResponseEntity<HourlyPredictionResponse> predictHourly(
            @RequestParam String routeNo,
            @RequestParam String arsNo,
            @RequestParam String date
    ) {
        return ResponseEntity.ok(predictionService.predictHourly(routeNo, arsNo, date));
    }

    /**
     * Updates hourly weight configuration.
     */
    @PutMapping("/weight")
    public ResponseEntity<Void> updateWeight(@RequestBody WeightUpdateRequest request) {
        predictionService.updateWeight(request);
        return ResponseEntity.noContent().build();
    }
}
