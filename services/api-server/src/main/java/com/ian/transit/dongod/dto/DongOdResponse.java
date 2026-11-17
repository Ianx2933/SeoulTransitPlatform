package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * Where the riders of one area go, or where the riders arriving there come
 * from, on a typical day, by rail (subway) and bus (TOPIS district OD).
 *
 * level is the area chosen (dong, gu or city) and destLevel the unit of the
 * rows (dong or gu). direction is "from" (rows are destinations) or "to"
 * (rows are origins). Trips inside the area are one of the rows.
 * 'outside' stands for everywhere outside Seoul.
 */
public record DongOdResponse(
    String level,
    String code,
    String name,
    String destLevel,
    String dayType,
    String direction,
    Double departures,
    Double arrivals,
    Double subwayDepartures,
    Double busDepartures,
    List<Flow> flows
) {
    public DongOdResponse {
        departures = TypicalDay.round(departures);
        arrivals = TypicalDay.round(arrivals);
        subwayDepartures = TypicalDay.round(subwayDepartures);
        busDepartures = TypicalDay.round(busDepartures);
    }

    public record Flow(String code, String name, Double subway, Double bus, Double total) {
        public Flow {
            subway = TypicalDay.round(subway);
            bus = TypicalDay.round(bus);
            total = TypicalDay.round(total);
        }
    }
}
