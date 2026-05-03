package com.ian.transit.common.exception;

/**
 * Exception used when requested domain data does not exist.
 * 
 * This separates expected "no data" cases from unexpected server failures.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
