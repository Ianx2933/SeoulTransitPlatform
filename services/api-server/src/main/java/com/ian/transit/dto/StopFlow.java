package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

/** Passengers going to, or coming from, one stop of the route. */
public record StopFlow(int seq, String ars, String name, Double passengers) {
    public StopFlow {
        passengers = TypicalDay.round(passengers);
    }
}
