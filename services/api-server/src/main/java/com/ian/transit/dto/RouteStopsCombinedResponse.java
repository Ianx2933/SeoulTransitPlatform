package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * Several stops of a route taken together (picked with Shift or Ctrl on the
 * page): riders boarding at any of them and where they go, riders alighting at
 * any of them and where they came from. inside: riders who board at one picked
 * stop and alight at another, counted in both lists and in both totals.
 */
public record RouteStopsCombinedResponse(String 기준일자, String 노선명, List<Integer> seqs, List<String> names,
                                         Double boardings, Double alightings, Double inside,
                                         List<StopFlow> destinations, List<StopFlow> origins) {
    public RouteStopsCombinedResponse {
        boardings = TypicalDay.round(boardings);
        alightings = TypicalDay.round(alightings);
        inside = TypicalDay.round(inside);
    }
}
