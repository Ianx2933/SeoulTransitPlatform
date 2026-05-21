package com.ian.transit.map.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when a requested node has no demand rows for the given parameters.
 *
 * Mapped to HTTP 404 so the frontend can distinguish "node not in dataset" from "node exists but has zero demand".
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class NodeNotFoundException extends RuntimeException {

    public NodeNotFoundException(String message) {
        super(message);
    }
}
