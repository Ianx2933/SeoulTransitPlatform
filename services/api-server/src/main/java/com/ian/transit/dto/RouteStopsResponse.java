package com.ian.transit.curatedod.dto;

import com.ian.transit.common.TypicalDay;

import java.util.List;

/**
 * Every stop of a route on a typical day, in sequence order.
 * unorderedPassengers: OD pairs whose alighting stop is not after the boarding
 * stop; they are in totalPassengers but in no stop figure.
 */
public record RouteStopsResponse(String 기준일자, String 노선명, Integer nDays, Double totalPassengers,
                                 Double unorderedPassengers, List<StopLoad> stops) {
    public RouteStopsResponse {
        totalPassengers = TypicalDay.round(totalPassengers);
        unorderedPassengers = TypicalDay.round(unorderedPassengers);
    }
}
