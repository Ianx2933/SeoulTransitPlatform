package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.dongod.application.BusOutside;
import com.ian.transit.dongod.application.DongReach;

import java.util.List;

/**
 * How far an area's district OD riders go (direction from) or come from (to):
 * riders a day by straight-line distance band between districts, by rail and
 * bus, with weighted medians in km. outside = rides to or from outside Seoul
 * (no distance), unknown = districts without a boundary.
 *
 * busOutside: bus riders to (from) the capital area outside Seoul counted from
 * route stop OD instead, by distance band and 시·군 (BusOutside); null for all
 * of Seoul.
 */
public record DongReachResponse(
    String level,
    String code,
    String dayType,
    String direction,
    Double total,
    Double subway,
    Double bus,
    Double outside,
    Double unknown,
    Double medianKm,
    Double meanKm,
    Double subwayMedianKm,
    Double busMedianKm,
    List<Band> bands,
    Bus busOutside
) {
    public record Band(String key, double minKm, Double maxKm, Double subway, Double bus, Double total) {
    }

    /** source = "route_stop_od": not TOPIS district OD, so not equal to outside above. */
    public record Bus(String source, Double total, Double unlocated, Double medianKm, Double meanKm,
                      List<Band> bands, List<Area> areas) {
    }

    /** code = first four digits of the 행정동 code; name null when unlisted. */
    public record Area(String code, String name, String nameEn, Double passengers) {
    }

    private static List<Band> bands(List<DongReach.Band> bands) {
        return bands.stream()
                .map(b -> new Band(b.key(), b.minKm(), b.maxKm(), TypicalDay.round(b.subway()),
                        TypicalDay.round(b.bus()), TypicalDay.round(b.total())))
                .toList();
    }

    public static Bus bus(BusOutside.Result r) {
        if (r == null) {
            return null;
        }
        List<Area> areas = r.areas().stream()
                .map(a -> new Area(a.code(), a.name(), a.nameEn(), TypicalDay.round(a.passengers())))
                .toList();
        return new Bus("route_stop_od", TypicalDay.round(r.total()), TypicalDay.round(r.unlocated()),
                r.medianKm(), r.meanKm(), bands(r.bands()), areas);
    }

    public static DongReachResponse of(String level, String code, String dayType, String direction,
                                       DongReach.Result r, BusOutside.Result busOutside) {
        return new DongReachResponse(level, code, dayType, direction, TypicalDay.round(r.total()),
                TypicalDay.round(r.subway()), TypicalDay.round(r.bus()), TypicalDay.round(r.outside()),
                TypicalDay.round(r.unknown()), r.medianKm(), r.meanKm(), r.subwayMedianKm(), r.busMedianKm(),
                bands(r.bands()), bus(busOutside));
    }
}
