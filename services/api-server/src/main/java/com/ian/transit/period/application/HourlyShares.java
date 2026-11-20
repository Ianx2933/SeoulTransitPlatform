package com.ian.transit.period.application;

import com.ian.transit.period.Hours;

import java.util.Map;

/**
 * Pure part of the hourly split, testable without BigQuery: the share of a
 * leg's daily riders that boarded in the chosen hours.
 *
 * Same rules as SeoulTransitPlatform's estimate_hourly_od.py:
 *  - the boarding stop is keyed by its ARS padded to five digits
 *    (normalize_ars); stops without one ('name:' piers, '00000') have no
 *    profile and are left out of an hourly view, as estimated_hourly_od leaves them out
 *  - a route stop with a profile uses it (hourly_profile already holds the
 *    weekday mean of Monday..Friday and the default for collapsed fits)
 *  - any other route stop uses the default profile
 */
public final class HourlyShares {

    private HourlyShares() {
    }

    /** estimate_hourly_od.py normalize_ars: digits padded to five, blank '00000', other text as is. */
    public static String normalizeArs(String ars) {
        if (ars == null) {
            return "00000";
        }
        String s = ars.strip();
        if (s.isEmpty() || s.equalsIgnoreCase("nan")) {
            return "00000";
        }
        if (!s.chars().allMatch(Character::isDigit)) {
            return s;
        }
        return s.length() >= 5 ? s : "0".repeat(5 - s.length()) + s;
    }

    /** A boarding stop the hourly split can use: a five-digit ARS other than 00000. */
    public static boolean splittable(String ars) {
        String k = normalizeArs(ars);
        return k.length() == 5 && k.chars().allMatch(Character::isDigit) && !k.equals("00000");
    }

    /**
     * Share of the day's riders of (route, boarding stop) in the hours, or -1
     * when the stop cannot be split (then the leg is left out).
     */
    public static double share(String route, String boardArs, Hours hours,
                               Map<String, double[]> profiles, double[] fallback) {
        if (!splittable(boardArs)) {
            return -1;
        }
        double[] profile = profiles.get(route.strip() + "|" + normalizeArs(boardArs));
        return hours.share(profile != null ? profile : fallback);
    }
}
