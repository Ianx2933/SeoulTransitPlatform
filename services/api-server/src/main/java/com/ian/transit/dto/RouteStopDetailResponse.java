package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * One stop of a route: where its boarding riders go, where its alighting riders
 * came from, and how far the riders on board as the bus leaves it carry.
 */
public record RouteStopDetailResponse(String 기준일자, String 노선명, int seq, String ars, String name,
                                      Double boardings, Double alightings, Double onBoardAfter,
                                      List<StopFlow> destinations, List<StopFlow> origins,
                                      List<StopReach> reach) {
    public RouteStopDetailResponse {
        boardings = TypicalDay.round(boardings);
        alightings = TypicalDay.round(alightings);
        onBoardAfter = TypicalDay.round(onBoardAfter);
    }
}
