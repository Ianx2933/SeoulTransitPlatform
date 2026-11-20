package com.ian.transit.period;

import com.ian.transit.common.TypicalDay;

/**
 * What OD a request asks for: a day type, and optionally a period of months
 * (otherwise the typical day of the last twelve months) and boarding hours
 * (otherwise the whole day).
 *
 * The typical day without hours is read from Cloud SQL as before. A period is
 * read from BigQuery. Hours split the riders by the boarding stop's hourly
 * profile (hourly_profile, the rules of estimate_hourly_od.py), for any day type.
 */
public record OdBasis(String dayType, Period period, Hours hours) {

    public static OdBasis of(String date, String period, String hours, Integer hour) {
        return new OdBasis(dayTypeOf(TypicalDay.label(date)), Period.parse(period), Hours.parse(hours, hour));
    }

    public static String dayTypeOf(String label) {
        return label.startsWith(TypicalDay.PREFIX) ? label.substring(TypicalDay.PREFIX.length()) : label;
    }

    /** The 기준일자 label of the typical day of this day type. */
    public String label() {
        return TypicalDay.PREFIX + dayType;
    }

    public boolean typical() {
        return period == null;
    }

    /** The plain typical day: everything from Cloud SQL, no BigQuery. */
    public boolean plain() {
        return period == null && hours == null;
    }

    /** "weekday", "weekday 2026-03..2026-05", "weekday 7-9h": messages and cache keys. */
    @Override
    public String toString() {
        return dayType + (period == null ? "" : " " + period) + (hours == null ? "" : " " + hours + "h");
    }
}
