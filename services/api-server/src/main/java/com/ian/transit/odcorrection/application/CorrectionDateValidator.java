package com.ian.transit.odcorrection.application;

import java.util.regex.Pattern;

/**
 * Strict validator for the OD-correction base date (기준일자).
 *
 * Every OD-correction workflow receives 기준일자 from an HTTP request parameter
 * and passes it into UPDATE/DELETE SQL. Enforcing a fixed 8-digit yyyyMMdd
 * shape at every service entry point guarantees that the value can never
 * carry SQL metacharacters, independent of how any individual query is built.
 */
public final class CorrectionDateValidator {

    private static final Pattern BASE_DATE_PATTERN = Pattern.compile("\\d{8}");

    private CorrectionDateValidator() {
    }

    /**
     * Returns the trimmed date or throws when the shape is not yyyyMMdd.
     */
    public static String requireValid(String 기준일자) {
        if (기준일자 == null) {
            throw new IllegalArgumentException("date must not be null");
        }

        String trimmed = 기준일자.trim();

        if (!BASE_DATE_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException("date must be 8 digits in yyyyMMdd form");
        }

        return trimmed;
    }
}
