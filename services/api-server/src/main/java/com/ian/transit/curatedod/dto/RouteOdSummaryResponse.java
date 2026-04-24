package com.ian.transit.curatedod.dto;

/**
 * Route-level OD passenger summary
 */
public record RouteOdSummaryResponse(
    String 기준일자,
    String 노선명,
    Long totalPassengers
) {
}
