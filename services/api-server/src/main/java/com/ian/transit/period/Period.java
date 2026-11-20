package com.ian.transit.period;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * A range of whole months chosen instead of the typical day (the last twelve
 * months): {@code period=2026-03..2026-05}, or one month {@code period=2026-03}.
 * At most {@link #MAX_MONTHS} months; months outside the data are refused by
 * the service that knows the data range. A bad value is an
 * IllegalArgumentException, which GlobalExceptionHandler answers with 400.
 */
public record Period(YearMonth from, YearMonth to) {

    public static final int MAX_MONTHS = 12;

    public Period {
        if (from == null || to == null) {
            throw new IllegalArgumentException("period needs a first and a last month");
        }
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("period ends before it starts: " + from + ".." + to);
        }
        if (months(from, to) > MAX_MONTHS) {
            throw new IllegalArgumentException("period is at most " + MAX_MONTHS + " months, got " + from + ".." + to);
        }
    }

    private static long months(YearMonth from, YearMonth to) {
        return ChronoUnit.MONTHS.between(from, to) + 1;
    }

    /** null for a missing or blank parameter (the typical day). */
    public static Period parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.trim();
        String[] parts = v.split("\\.\\.", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("period must be YYYY-MM or YYYY-MM..YYYY-MM, got '" + v + "'");
        }
        try {
            YearMonth from = YearMonth.parse(parts[0].trim());
            YearMonth to = parts.length == 2 ? YearMonth.parse(parts[1].trim()) : from;
            return new Period(from, to);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("period must be YYYY-MM or YYYY-MM..YYYY-MM, got '" + v + "'");
        }
    }

    public int monthCount() {
        return (int) months(from, to);
    }

    /** First day of the first month, as od_period_days.month holds it. */
    public LocalDate firstMonthDay() {
        return from.atDay(1);
    }

    /** First day of the last month. */
    public LocalDate lastMonthDay() {
        return to.atDay(1);
    }

    /** Last calendar day of the period, for partition pruning. */
    public LocalDate lastDay() {
        return to.atEndOfMonth();
    }

    /** "2026-03..2026-05" or "2026-03": also the cache key part. */
    @Override
    public String toString() {
        return from.equals(to) ? from.toString() : from + ".." + to;
    }
}
