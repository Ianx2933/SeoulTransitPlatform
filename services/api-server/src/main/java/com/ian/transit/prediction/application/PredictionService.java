package com.ian.transit.prediction.application;

import com.ian.transit.prediction.client.PredictionClient;
import com.ian.transit.prediction.dto.HourlyPredictionResponse;
import com.ian.transit.prediction.dto.PredictionRequest;
import com.ian.transit.prediction.dto.WeightUpdateRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Application service that orchestrates prediction use cases.
 * 
 * Spring owns request validation and operational feature preparation,
 * while Flask is kept focused on model inference.
 */
@Service
@RequiredArgsConstructor
public class PredictionService {

    private static final DateTimeFormatter BASIC_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final PredictionFeatureService predictionFeatureService;
    private final PredictionClient predictionClient;

    /**
     * Builds an inference request and calls the Flask prediction service.
     */
    public HourlyPredictionResponse predictHourly(String routeNo, String arsNo, String date) {
        String normalizedRouteNo = normalizeRequired(routeNo, "routeNo");
        String normalizedArsNo = normalizeArs(arsNo);
        LocalDate targetDate = parseDate(date);

        PredictionRequest request = predictionFeatureService.buildPredictionRequest(
                normalizedRouteNo,
                normalizedArsNo,
                targetDate
        );

        return predictionClient.predictHourly(request);
    }

    /**
     * Sends weight update request to the Flask prediction service.
     */
    public void updateWeight(WeightUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("weight update request must not be null");
        }
        predictionClient.updateWeight(request);
    }

    private LocalDate parseDate(String date) {
        String normalizedDate = normalizeRequired(date, "date");
        try {
            return LocalDate.parse(normalizedDate, BASIC_DATE_FORMAT);
        } catch (Exception exception) {
            throw new IllegalArgumentException("date must use yyyyMMdd format");
        }
    }

    /**
     * Normalizes ARS to a 5-digit string. Example: 1267 -> 01267.
     * This prevents prediction failures caused by inconsistent ARS formatting
     * between DB tables, model maps, and user input.
     */
    private String normalizeArs(String arsNo) {
        String normalizedValue = normalizeRequired(arsNo, "arsNo");
        if (!normalizedValue.matches("\\d+")) {
            throw new IllegalArgumentException("arsNo must contain digits only");
        }
        if (normalizedValue.length() > 5) {
            throw new IllegalArgumentException("arsNo must be at most 5 digits");
        }
        return normalizedValue.length() == 5 ? normalizedValue : "0".repeat(5 - normalizedValue.length()) + normalizedValue;
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalizedValue = value == null ? "" : value.trim();
        if (normalizedValue.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalizedValue;
    }
}
