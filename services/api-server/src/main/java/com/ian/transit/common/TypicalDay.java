package com.ian.transit.common;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The typical days the OD endpoints serve.
 *
 * Since 2026-11 Cloud SQL holds no dated OD days for serving. It holds the mean
 * OD of a day type over the last twelve months (table od_typical_route_stop),
 * stored with a label in 기준일자: typical_weekday, typical_saturday,
 * typical_sunday_holiday. The endpoints keep their {@code date} parameter and
 * take that label, or the day type alone, and default to the typical weekday.
 * A YYYYMMDD date is refused with the allowed values rather than answered with
 * an empty list.
 */
public final class TypicalDay {

    public static final String PREFIX = "typical_";
    public static final String DEFAULT_LABEL = "typical_weekday";
    public static final List<String> DAY_TYPES = List.of("weekday", "saturday", "sunday_holiday");

    private TypicalDay() {
    }

    /** The 기준일자 label for a request's date parameter. */
    public static String label(String requested) {
        if (requested == null || requested.isBlank()) {
            return DEFAULT_LABEL;
        }
        String value = requested.trim();
        String dayType = value.startsWith(PREFIX) ? value.substring(PREFIX.length()) : value;
        if (DAY_TYPES.contains(dayType)) {
            return PREFIX + dayType;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "date must be one of " + DAY_TYPES + " (or with the typical_ prefix); "
                + "OD is served as typical days, not dated days, got '" + value + "'");
    }
}
