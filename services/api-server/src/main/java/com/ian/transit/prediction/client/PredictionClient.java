package com.ian.transit.prediction.client;

import com.ian.transit.prediction.dto.HourlyPredictionResponse;
import com.ian.transit.prediction.dto.PredictionRequest;
import com.ian.transit.prediction.dto.WeightUpdateRequest;
import lombok.RequiredArgsConstructor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

/**
 * HTTP client for the Flask prediction microservice.
 *
 * All external model-serving calls are centralized here so that
 * the service layer does not depend on HTTP details.
 */
@Component
@RequiredArgsConstructor
public class PredictionClient {

    private final RestTemplate restTemplate;

    @Value("${prediction.service.base-url:http://localhost:5000}")
    private String predictionServiceBaseUrl;

    /**
     * Calls Flask /predict endpoint to get daily and hourly prediction.
     */
    public HourlyPredictionResponse predictHourly(PredictionRequest request) {

        HttpEntity<PredictionRequest> entity = new HttpEntity<>(request, jsonHeaders());

        ResponseEntity<HourlyPredictionResponse> response = restTemplate.exchange(
                predictionServiceBaseUrl + "/predict",
                HttpMethod.POST,
                entity,
                HourlyPredictionResponse.class
        );

        // Defensive check to avoid null propagation.
        if (response.getBody() == null) {
            throw new IllegalStateException("Prediction service returned empty response");
        }

        return response.getBody();
    }

    /**
     * Calls Flask /weight/update endpoint for hourly ratio tuning.
     *
     * Weight persistence is not implemented in the prediction service yet, so
     * Flask answers 501 after validating the payload. That 501 is translated
     * into {@link UnsupportedOperationException}, which the global exception
     * handler maps back to HTTP 501 — the caller is never shown a fake
     * success.
     */
    public void updateWeight(WeightUpdateRequest request) {

        HttpEntity<WeightUpdateRequest> entity = new HttpEntity<>(request, jsonHeaders());

        try {
            ResponseEntity<Void> response = restTemplate.exchange(
                    predictionServiceBaseUrl + "/weight/update",
                    HttpMethod.PUT,
                    entity,
                    Void.class
            );

            // Ensures weight update is successfully applied.
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new IllegalStateException("Prediction weight update failed");
            }
        } catch (HttpServerErrorException exception) {
            if (exception.getStatusCode().value() == HttpStatus.NOT_IMPLEMENTED.value()) {
                throw new UnsupportedOperationException(
                        "Prediction weight persistence is not implemented yet"
                );
            }
            throw exception;
        }
    }

    /**
     * Builds JSON HTTP headers for Flask communication.
     *
     * Spring HttpHeaders must be used (not java.net.http.HttpHeaders),
     * otherwise Content-Type cannot be set.
     */
    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
