package com.ian.transit.dongod.application;

import com.ian.transit.dongod.dto.DongOdResponse;
import com.ian.transit.dongod.dto.DongOdSummaryRow;
import com.ian.transit.dongod.infrastructure.DongOdQueryRepository;
import com.ian.transit.period.infrastructure.PeriodDongRepository.Pair;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The district OD answers of DongOdQueryRepository (flows, totals, summary,
 * reach rows) computed in Java from averaged pairs, for a period read from
 * BigQuery. Each method follows the SQL it stands for, so a period equal to
 * the typical window gives the typical numbers. Pure, for tests.
 */
public final class DistrictOd {

    /** PostGIS ST_DistanceSphere radius, metres. */
    static final double SPHERE_RADIUS_M = 6370986.0;

    private DistrictOd() {
    }

    /** filterSql of the level, on a district code. */
    public static boolean inArea(DongArea level, String code, String x) {
        if (x == null) {
            return false;
        }
        return switch (level) {
            case DONG -> x.equals(code);
            case GU -> !DongArea.OUTSIDE.equals(x) && x.startsWith(code);
            case CITY -> !DongArea.OUTSIDE.equals(x);
            case GROUP -> DongArea.groupCodes(code).contains(x);
        };
    }

    /** unitSql of the level, on a district code. */
    public static String unit(DongArea level, String x) {
        if (DongArea.OUTSIDE.equals(x)) {
            return DongArea.OUTSIDE;
        }
        return switch (level) {
            case DONG, GROUP -> x;
            case GU -> x.substring(0, Math.min(5, x.length()));
            case CITY -> DongArea.SEOUL;
        };
    }

    public static DongOdQueryRepository.Totals totals(List<Pair> pairs, DongArea level, String code) {
        double dep = 0, arr = 0, s = 0, b = 0;
        String name = null;
        for (Pair p : pairs) {
            if (inArea(level, code, p.originCd())) {
                dep += p.total();
                s += p.subway();
                b += p.bus();
                if (p.originCd().equals(code) && p.originName() != null) {
                    name = p.originName();
                }
            }
            if (inArea(level, code, p.destCd())) {
                arr += p.total();
                if (name == null && p.destCd().equals(code) && p.destName() != null) {
                    name = p.destName();
                }
            }
        }
        return new DongOdQueryRepository.Totals(dep, arr, s, b, name);
    }

    public static List<DongOdResponse.Flow> flows(List<Pair> pairs, DongArea level, String code, DongArea destLevel,
                                                  boolean from, int limit) {
        record Acc(double[] v, String[] name) {
        }
        Map<String, Acc> by = new LinkedHashMap<>();
        for (Pair p : pairs) {
            String self = from ? p.originCd() : p.destCd();
            if (!inArea(level, code, self)) {
                continue;
            }
            String other = from ? p.destCd() : p.originCd();
            String otherName = from ? p.destName() : p.originName();
            Acc a = by.computeIfAbsent(unit(destLevel, other), k -> new Acc(new double[3], new String[1]));
            a.v()[0] += p.subway();
            a.v()[1] += p.bus();
            a.v()[2] += p.total();
            if (otherName != null && (a.name()[0] == null || otherName.compareTo(a.name()[0]) < 0)) {
                a.name()[0] = otherName;                                     // MIN(name) as in the SQL
            }
        }
        List<DongOdResponse.Flow> out = new ArrayList<>();
        by.forEach((u, a) -> out.add(new DongOdResponse.Flow(u, destLevel.nameOf(u, a.name()[0]),
                a.v()[0], a.v()[1], a.v()[2])));
        out.sort(Comparator.comparing(DongOdResponse.Flow::total).reversed().thenComparing(DongOdResponse.Flow::code));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public static List<DongOdSummaryRow> summary(List<Pair> pairs, DongArea level) {
        Map<String, double[]> by = new LinkedHashMap<>();          // t, s, b departures; t, s, b arrivals
        for (Pair p : pairs) {
            if (!DongArea.OUTSIDE.equals(p.originCd())) {
                double[] v = by.computeIfAbsent(unit(level, p.originCd()), k -> new double[6]);
                v[0] += p.total();
                v[1] += p.subway();
                v[2] += p.bus();
            }
            if (!DongArea.OUTSIDE.equals(p.destCd())) {
                double[] v = by.computeIfAbsent(unit(level, p.destCd()), k -> new double[6]);
                v[3] += p.total();
                v[4] += p.subway();
                v[5] += p.bus();
            }
        }
        List<DongOdSummaryRow> out = new ArrayList<>();
        by.forEach((c, v) -> out.add(new DongOdSummaryRow(c, v[0], v[3], v[1], v[2], v[4], v[5])));
        return out;
    }

    /** reachRows: the area's pairs with km between district points (0 inside one, null to or from outside). */
    public static List<DongReach.Row> reachRows(List<Pair> pairs, DongArea level, String code, boolean from,
                                                Map<String, double[]> points) {
        List<DongReach.Row> out = new ArrayList<>();
        for (Pair p : pairs) {
            String here = from ? p.originCd() : p.destCd();
            if (!inArea(level, code, here)) {
                continue;
            }
            String there = from ? p.destCd() : p.originCd();
            boolean outside = DongArea.OUTSIDE.equals(here) || DongArea.OUTSIDE.equals(there);
            Double km;
            if (outside) {
                km = null;
            } else if (here.equals(there)) {
                km = 0.0;
            } else {
                double[] a = points.get(here);
                double[] b = points.get(there);
                km = a == null || b == null ? null : km(a, b);
            }
            out.add(new DongReach.Row(km, outside, p.subway(), p.bus(), p.total()));
        }
        return out;
    }

    /** Great-circle km between [lat, lng] points, as ST_DistanceSphere. */
    public static double km(double[] a, double[] b) {
        double la1 = Math.toRadians(a[0]);
        double la2 = Math.toRadians(b[0]);
        double dLa = la2 - la1;
        double dLo = Math.toRadians(b[1] - a[1]);
        double h = Math.sin(dLa / 2) * Math.sin(dLa / 2) + Math.cos(la1) * Math.cos(la2) * Math.sin(dLo / 2) * Math.sin(dLo / 2);
        return 2 * SPHERE_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(h))) / 1000.0;
    }
}
