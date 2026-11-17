package com.ian.transit.dongod.dto;

import com.ian.transit.common.TypicalDay;

/** Trips leaving and reaching one district on a typical day (for map colours). */
public record DongOdSummaryRow(String code, Double departures, Double arrivals) {
    public DongOdSummaryRow {
        departures = TypicalDay.round(departures);
        arrivals = TypicalDay.round(arrivals);
    }
}
