package com.ian.transit.terrain.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Scoped to terrain endpoints; existing API error contracts are untouched. */
@RestControllerAdvice(assignableTypes = TerrainAccessibilityController.class)
public class TerrainExceptionHandler {
    @ExceptionHandler(TerrainBadRequestException.class)
    public ResponseEntity<TerrainErrorResponse> badRequest(TerrainBadRequestException ex) {
        return ResponseEntity.badRequest().body(new TerrainErrorResponse("INVALID_TERRAIN_REQUEST", ex.getMessage()));
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class})
    public ResponseEntity<TerrainErrorResponse> invalidParameter(Exception ex) {
        return ResponseEntity.badRequest().body(new TerrainErrorResponse(
                "INVALID_TERRAIN_REQUEST", "Invalid or missing query parameter"));
    }

    @ExceptionHandler(TerrainUnavailableException.class)
    public ResponseEntity<TerrainErrorResponse> unavailable(TerrainUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                new TerrainErrorResponse("TERRAIN_DATA_UNAVAILABLE", ex.getMessage()));
    }
}
