package com.ian.transit.period;

/**
 * Boarding hours to keep: {@code hours=8} (one hour) or {@code hours=7-9}
 * (7:00 to 9:59). Riders are split by the hour they boarded, so a segment's
 * figure is "riders who boarded in these hours and ride this segment".
 */
public record Hours(int from, int to) {

    public Hours {
        if (from < 0 || from > 23 || to < 0 || to > 23) {
            throw new IllegalArgumentException("hours must be 0 to 23, got " + from + "-" + to);
        }
        if (to < from) {
            throw new IllegalArgumentException("hours must run forward (e.g. 7-9), got " + from + "-" + to);
        }
    }

    /** hours=7-9 or hours=8; the older hour=8 is taken when hours is missing. null = the whole day. */
    public static Hours parse(String hours, Integer hour) {
        if (hours != null && !hours.isBlank()) {
            String v = hours.trim();
            String[] parts = v.split("-", -1);
            try {
                if (parts.length == 1) {
                    int h = Integer.parseInt(parts[0].trim());
                    return new Hours(h, h);
                }
                if (parts.length == 2) {
                    return new Hours(Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()));
                }
            } catch (NumberFormatException e) {
                // falls through to the message below
            }
            throw new IllegalArgumentException("hours must be H or H-H (0-23), got '" + v + "'");
        }
        return hour == null ? null : new Hours(hour, hour);
    }

    public boolean single() {
        return from == to;
    }

    /** Share of a day's riders in these hours under a 24-value boarding profile. */
    public double share(double[] profile) {
        double s = 0;
        for (int h = from; h <= to; h++) {
            s += profile[h];
        }
        return s;
    }

    @Override
    public String toString() {
        return single() ? Integer.toString(from) : from + "-" + to;
    }
}
