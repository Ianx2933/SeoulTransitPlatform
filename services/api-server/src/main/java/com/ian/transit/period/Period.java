package com.ian.transit.period;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * A range of whole months chosen instead of the typical day (the last twelve
 * months): {@code period=2026-03..2026-05}, or one month {@code period=2026-03},
 * or one TOPIS day {@code period=2026-09-15} (day set, from = to = its month).
 * At most {@link #MAX_MONTHS} months; months outside the data are refused by
 * the service that knows the data range. A bad value is an
 * IllegalArgumentException, which GlobalExceptionHandler answers with 400.
 */
public record Period(YearMonth from, YearMonth to, LocalDate day) {

    public static final int MAX_MONTHS = 12;

    public Period(YearMonth from, YearMonth to) {
        this(from, to, null);
    }

    public static Period ofDay(LocalDate day) {
        YearMonth month = YearMonth.from(day);
        return new Period(month, month, day);
    }

    public Period {
        if (from == null || to == null) {
            throw new IllegalArgumentException("period needs a first and a last month");
        }
        if (day != null && (!from.equals(to) || !YearMonth.from(day).equals(from))) {
            throw new IllegalArgumentException("a one-day period lies in its own month: " + day);
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
        if (v.matches("\\d{4}-\\d{2}-\\d{2}")) {
            try {
                return ofDay(LocalDate.parse(v));
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("period day must be a date YYYY-MM-DD, got '" + v + "'");
            }
        }
        String[] parts = v.split("\\.\\.", -1);
        if (parts.length > 2) {
            throw new IllegalArgumentException("period must be YYYY-MM, YYYY-MM..YYYY-MM or YYYY-MM-DD, got '" + v + "'");
        }
        try {
            YearMonth from = YearMonth.parse(parts[0].trim());
            YearMonth to = parts.length == 2 ? YearMonth.parse(parts[1].trim()) : from;
            return new Period(from, to);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("period must be YYYY-MM, YYYY-MM..YYYY-MM or YYYY-MM-DD, got '" + v + "'");
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

    /** First calendar day of the period (the day itself for a one-day period). */
    public LocalDate firstDay() {
        return day != null ? day : from.atDay(1);
    }

    /** Last calendar day of the period, for partition pruning (the day itself for a one-day period). */
    public LocalDate lastDay() {
        return day != null ? day : to.atEndOfMonth();
    }

    public boolean oneDay() {
        return day != null;
    }

    /** "2026-03..2026-05", "2026-03" or "2026-09-15": also the cache key part. */
    @Override
    public String toString() {
        if (day != null) {
            return day.toString();
        }
        return from.equals(to) ? from.toString() : from + ".." + to;
    }
}
