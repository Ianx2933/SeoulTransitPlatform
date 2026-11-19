package com.ian.transit.dongod.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * How far district OD rides go: the straight-line distance between the two
 * districts' points (ST_PointOnSurface), weighted by riders, in bands and as
 * a median. A ride inside one district counts as 0 km. Rides to or from
 * outside Seoul have no distance (TOPIS gives one "outside" code); rides whose
 * district has no boundary are counted as unknown.
 *
 * Straight line between district points, not the route ridden: it tells near
 * from far, not the length of the trip.
 */
public final class DongReach {

    private DongReach() {
    }

    /** km is null for outside or unknown; outside tells which. */
    public record Row(Double km, boolean outside, double subway, double bus, double total) {
    }

    /** [minKm, maxKm); key 0 is the same district. */
    public record Band(String key, double minKm, Double maxKm, double subway, double bus, double total) {
    }

    /** outsideBus = the bus part of outside (BusOutside counts part of it by district). */
    public record Result(double total, double subway, double bus, double outside, double unknown,
                         Double medianKm, Double meanKm, Double subwayMedianKm, Double busMedianKm,
                         List<Band> bands, double outsideBus) {
    }

    /** Upper edges of the bands after "same district"; the last band is open. */
    static final double[] EDGES = {1, 3, 5, 10, 20};

    public static Result summarize(List<Row> rows) {
        double total = 0, subway = 0, bus = 0, outside = 0, unknown = 0, outsideBus = 0;
        List<Row> known = new ArrayList<>();
        for (Row r : rows) {
            total += r.total();
            subway += r.subway();
            bus += r.bus();
            if (r.km() != null) {
                known.add(r);
            } else if (r.outside()) {
                outside += r.total();
                outsideBus += r.bus();
            } else {
                unknown += r.total();
            }
        }
        known.sort(Comparator.comparingDouble(Row::km));

        List<Band> bands = new ArrayList<>();
        bands.add(band("same", 0, 0.0, known, true));
        double lo = 0;
        for (double hi : EDGES) {
            bands.add(band(key(lo, hi), lo, hi, known, false));
            lo = hi;
        }
        bands.add(band(key(lo, null), lo, null, known, false));

        double weight = known.stream().mapToDouble(Row::total).sum();
        Double mean = weight > 0 ? round1(known.stream().mapToDouble(r -> r.km() * r.total()).sum() / weight) : null;
        return new Result(total, subway, bus, outside, unknown,
                median(known, Row::total), mean, median(known, Row::subway), median(known, Row::bus), bands, outsideBus);
    }

    private static String key(double lo, Double hi) {
        String a = trim(lo);
        return hi == null ? a + "+" : a + "-" + trim(hi);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Riders in the band; the same-district band takes km == 0, the 0–1 band then starts above 0. */
    private static Band band(String key, double lo, Double hi, List<Row> rows, boolean same) {
        double s = 0, b = 0, t = 0;
        for (Row r : rows) {
            double km = r.km();
            boolean in = same ? km == 0 : km > 0 && km >= lo && (hi == null || km < hi);
            if (in) {
                s += r.subway();
                b += r.bus();
                t += r.total();
            }
        }
        return new Band(key, lo, hi, s, b, t);
    }

    /** Weighted median of km over rows sorted by km; null without weight. */
    static Double median(List<Row> sorted, ToDoubleFunction<Row> weightOf) {
        double weight = sorted.stream().mapToDouble(weightOf).sum();
        if (weight <= 0) {
            return null;
        }
        double half = weight / 2, run = 0;
        for (Row r : sorted) {
            run += weightOf.applyAsDouble(r);
            if (run >= half) {
                return round1(r.km());
            }
        }
        return round1(sorted.get(sorted.size() - 1).km());
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
