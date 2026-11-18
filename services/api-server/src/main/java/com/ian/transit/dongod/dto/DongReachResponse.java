package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;
import com.ian.transit.dongod.application.DongReach;

import java.util.List;

/**
 * How far an area's district OD riders go (direction from) or come from (to):
 * riders a day by straight-line distance band between districts, by rail and
 * bus, with weighted medians in km. outside = rides to or from outside Seoul
 * (no distance), unknown = districts without a boundary.
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
    List<Band> bands
) {
    public record Band(String key, double minKm, Double maxKm, Double subway, Double bus, Double total) {
    }

    public static DongReachResponse of(String level, String code, String dayType, String direction, DongReach.Result r) {
        List<Band> bands = r.bands().stream()
                .map(b -> new Band(b.key(), b.minKm(), b.maxKm(), TypicalDay.round(b.subway()),
                        TypicalDay.round(b.bus()), TypicalDay.round(b.total())))
                .toList();
        return new DongReachResponse(level, code, dayType, direction, TypicalDay.round(r.total()),
                TypicalDay.round(r.subway()), TypicalDay.round(r.bus()), TypicalDay.round(r.outside()),
                TypicalDay.round(r.unknown()), r.medianKm(), r.meanKm(), r.subwayMedianKm(), r.busMedianKm(), bands);
    }
}
