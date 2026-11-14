package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * Demand of one segment of a route, from stop {@code fromSeq} to stop {@code toSeq}.
 *
 * within: board and alight inside. leaving: board inside, alight past the end.
 * entering: board before the start, alight inside. through: board before the
 * start, alight past the end. Shares are of the route total.
 */
public record RouteSegmentResponse(String 기준일자, String 노선명, int fromSeq, String fromName,
                                   int toSeq, String toName, Double routeTotal, Double within,
                                   Double leaving, Double entering, Double through,
                                   List<SegmentStop> stops, List<StopFlow> leavingTo,
                                   List<StopFlow> enteringFrom) {
    public RouteSegmentResponse {
        routeTotal = TypicalDay.round(routeTotal);
        within = TypicalDay.round(within);
        leaving = TypicalDay.round(leaving);
        entering = TypicalDay.round(entering);
        through = TypicalDay.round(through);
    }
}
