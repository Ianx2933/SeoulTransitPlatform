package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * Where the riders of one district (행정동) go, or where the riders arriving
 * there come from, on a typical day, all modes (TOPIS district OD).
 *
 * direction is "from" (rows are destinations) or "to" (rows are origins).
 * Trips inside the district are one of the rows. 'outside' stands for
 * everywhere outside Seoul.
 */
public record DongOdResponse(
    String code,
    String name,
    String dayType,
    String direction,
    Double departures,
    Double arrivals,
    List<Flow> flows
) {
    public DongOdResponse {
        departures = TypicalDay.round(departures);
        arrivals = TypicalDay.round(arrivals);
    }

    public record Flow(String code, String name, Double subway, Double bus, Double total) {
        public Flow {
            subway = TypicalDay.round(subway);
            bus = TypicalDay.round(bus);
            total = TypicalDay.round(total);
        }
    }
}
