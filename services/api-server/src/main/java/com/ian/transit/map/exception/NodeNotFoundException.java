package com.ian.transit.map.exception;

import com.ian.transit.common.exception.NotFoundException;

/**
 * Domain-specific not-found error for map node demand.
 *
 * Extending the shared NotFoundException keeps the HTTP 404 contract aligned
 * with GlobalExceptionHandler instead of falling through to its generic 500
 * handler.
 */
public class NodeNotFoundException extends NotFoundException {

    public NodeNotFoundException(String message) {
        super(message);
    }
}
