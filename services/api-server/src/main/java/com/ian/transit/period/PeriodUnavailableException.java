package com.ian.transit.period;

/**
 * BigQuery could not answer a period or hourly request (off, over the byte cap,
 * timed out, failed). Answered with 503 so the page can offer the typical day,
 * which does not need BigQuery.
 */
public class PeriodUnavailableException extends RuntimeException {

    public PeriodUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public PeriodUnavailableException(String message) {
        super(message);
    }
}
