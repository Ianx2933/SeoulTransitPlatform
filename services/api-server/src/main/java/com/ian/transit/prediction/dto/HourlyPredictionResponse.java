package com.ian.transit.prediction.dto;

import java.util.List;

/**
 * Hourly prediction response returned to API consumers.
 *
 * dailyPrediction is the model's daily demand estimate, and hourlyPrediction
 * distributes that demand into 24 hourly boarding/alighting values.
 */
public record HourlyPredictionResponse(
        double dailyPrediction,
        List<HourlyPrediction> hourlyPrediction
) {
    /**
     * One hourly prediction entry.
     */
    public record HourlyPrediction(int hour, double boarding, double alighting) {
    }
}
