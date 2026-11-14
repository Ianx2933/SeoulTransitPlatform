package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

/** One stop of a route: riders boarding and alighting there, and on board as the bus leaves it. */
public record StopLoad(int seq, String ars, String name, Double boardings, Double alightings, Double onBoardAfter) {
    public StopLoad {
        boardings = TypicalDay.round(boardings);
        alightings = TypicalDay.round(alightings);
        onBoardAfter = TypicalDay.round(onBoardAfter);
    }
}
