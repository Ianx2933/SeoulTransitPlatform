package com.ian.transit.dongod.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bus riders between an area and the capital area outside Seoul, counted from
 * route stop OD (od_typical_route_stop) instead of TOPIS district OD, which
 * gives everywhere outside Seoul one code. The stop at the other end is placed
 * in its 행정동 (stop tables + stop_location_fix, like the bus routes tab);
 * the distance is between the two districts' points, as in DongReach.
 *
 * Bus only: rail OD by station covers one weekday a month. A second source,
 * so it does not add up exactly to district OD's outside figure (the two
 * differ by about 0.14% a day citywide).
 */
public final class BusOutside {

    public static final int TOP_AREAS = 10;

    private BusOutside() {
    }

    /**
     * Riders to (or from) one district outside Seoul. admCd null = the stop at
     * the other end has no location or lies in no district (it may be in Seoul).
     */
    public record Row(String admCd, String admNm, Double km, double passengers) {
    }

    /** Riders by 시·군 (CapitalAreaSigungu key); name null when the key is not listed. */
    public record Area(String code, String name, String nameEn, double passengers) {
    }

    public record Result(double total, double unlocated, Double medianKm, Double meanKm,
                         List<DongReach.Band> bands, List<Area> areas) {
    }

    public static Result summarize(List<Row> rows) {
        List<DongReach.Row> measured = new ArrayList<>();
        double unlocated = 0;
        Map<String, double[]> byKey = new LinkedHashMap<>();
        for (Row r : rows) {
            if (r.admCd() == null || r.km() == null) {
                unlocated += r.passengers();
                continue;
            }
            measured.add(new DongReach.Row(r.km(), false, 0, r.passengers(), r.passengers()));
            byKey.computeIfAbsent(CapitalAreaSigungu.key(r.admCd()), k -> new double[1])[0] += r.passengers();
        }
        DongReach.Result reach = DongReach.summarize(measured);
        // Never 0 km: the other district is outside Seoul, so "same district" is dropped.
        List<DongReach.Band> bands = reach.bands().stream().filter(b -> !"same".equals(b.key())).toList();
        List<Area> areas = byKey.entrySet().stream()
                .sorted(Map.Entry.<String, double[]>comparingByValue(Comparator.comparingDouble(v -> -v[0])))
                .limit(TOP_AREAS)
                .map(e -> {
                    CapitalAreaSigungu.Name n = CapitalAreaSigungu.name(e.getKey());
                    return new Area(e.getKey(), n == null ? null : n.ko(), n == null ? null : n.en(), e.getValue()[0]);
                })
                .toList();
        return new Result(reach.total(), unlocated, reach.medianKm(), reach.meanKm(), bands, areas);
    }
}
