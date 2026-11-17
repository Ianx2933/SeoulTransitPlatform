package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;

/** Trips leaving and reaching one area on a typical day, by mode (for map colours). */
public record DongOdSummaryRow(
    String code,
    Double departures,
    Double arrivals,
    Double subwayDepartures,
    Double busDepartures,
    Double subwayArrivals,
    Double busArrivals
) {
    public DongOdSummaryRow {
        departures = TypicalDay.round(departures);
        arrivals = TypicalDay.round(arrivals);
        subwayDepartures = TypicalDay.round(subwayDepartures);
        busDepartures = TypicalDay.round(busDepartures);
        subwayArrivals = TypicalDay.round(subwayArrivals);
        busArrivals = TypicalDay.round(busArrivals);
    }
}
