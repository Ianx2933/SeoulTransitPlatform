package com.ian.transit.prediction.dto;

/**
 * Request body sent from Spring API server to Flask prediction service.
 * 
 * This DTO contains model-ready features, not raw user input.
 * 
 * dayOfWeek follows Python/Pandas convention: Monday=0, Sunday=6.
 */
public record PredictionRequest(
        String routeNo,
        String arsNo,
        int dayOfWeek,
        int isHoliday,
        int month,
        double prevWeek,
        double prevMonth
) {
}
