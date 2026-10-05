package com.ian.transit.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.LocalDateTime;

/**
 * Global REST exception handler.
 *
 * Policy: 4xx responses carry the validation message because it is safe and
 * user-actionable. 5xx responses carry a FIXED message only — raw exception
 * messages can leak table names, connection details, or stack fragments, so
 * they go to the server log instead of the HTTP body.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final String INTERNAL_ERROR_MESSAGE = "Internal server error";

    private static final String NOT_FOUND_MESSAGE = "Resource not found";

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(
            NotFoundException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                exception.getMessage(),
                request
        );
    }

    /**
     * Unmapped URLs.
     *
     * Without this handler these fall through to the catch-all below and are
     * reported as 500, which hides real server errors among ordinary typos.
     * Spring Boot routes unmatched requests to the static resource handler,
     * so NoResourceFoundException is the usual case; NoHandlerFoundException
     * is kept for the throwExceptionIfNoHandlerFound configuration.
     *
     * The exception message is NOT echoed back: it names the resource lookup
     * path, which tells a caller more about internal routing than they need.
     */
    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class
    })
    public ResponseEntity<ErrorResponse> handleUnmappedPath(
            Exception exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_FOUND,
                NOT_FOUND_MESSAGE,
                request
        );
    }

    /**
     * Right path, wrong HTTP verb — 405 rather than 500.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.METHOD_NOT_ALLOWED,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                request
        );
    }

    @ExceptionHandler({
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    public ResponseEntity<ErrorResponse> handleRequestBinding(
            Exception exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.BAD_REQUEST,
                exception.getMessage(),
                request
        );
    }

    /**
     * Features that are exposed in the API surface but intentionally not yet
     * implemented (e.g. prediction weight persistence) map to 501 so callers
     * are not misled by a fake success.
     */
    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<ErrorResponse> handleNotImplemented(
            UnsupportedOperationException exception,
            HttpServletRequest request
    ) {
        return buildResponse(
                HttpStatus.NOT_IMPLEMENTED,
                exception.getMessage(),
                request
        );
    }

    /**
     * Catch-all handler for unexpected errors.
     *
     * The full stack trace is logged server-side; the HTTP body deliberately
     * exposes nothing about the failure internals.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpectedException(
            Exception exception,
            HttpServletRequest request
    ) {
        log.error(
                "Unhandled exception at {} {}",
                request.getMethod(),
                request.getRequestURI(),
                exception
        );

        return buildResponse(
                HttpStatus.INTERNAL_SERVER_ERROR,
                INTERNAL_ERROR_MESSAGE,
                request
        );
    }

    private ResponseEntity<ErrorResponse> buildResponse(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        ErrorResponse response = new ErrorResponse(
                LocalDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(response);
    }
}
