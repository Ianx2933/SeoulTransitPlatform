package com.ian.transit.common.exception;

import java.time.LocalDateTime;

/**
 * Standard API error response.
 * 
 * A consistent error shape helps the frontend and API clients handle failures uniformly.
 */
public record ErrorResponse(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path
) {
}
